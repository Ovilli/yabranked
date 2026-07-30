package dev.yabranked.backend.api

import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.PartyClientMessage
import dev.yabranked.proto.PartyOptions
import dev.yabranked.proto.PartyServerMessage
import dev.yabranked.proto.SessionRequest
import dev.yabranked.proto.SessionResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The party socket as a client actually uses it.
 *
 * [dev.yabranked.backend.social.PartyServiceTest] covers the rules; this covers
 * the thing they ride on — that the route exists, that a client's messages reach
 * the service, and above all that a pushed event (an invite) actually arrives at
 * the *other* player's socket. That last one is the whole point of the channel
 * and the one thing the service tests cannot prove.
 */
class PartySocketTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
    private val queueService = QueueService(MatchmakingQueue(), matchService)
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private val deps = ApiDependencies(
        verifier = FakeSessionVerifier(),
        players = players,
        matches = matches,
        matchService = matchService,
        queueService = queueService,
    )

    private fun ApplicationTestBuilder.wsClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(WebSockets)
    }

    private suspend fun ApplicationTestBuilder.login(name: String): SessionResponse =
        wsClient().post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest(name, "serverid-$name"))
        }.body()

    private fun encode(message: PartyClientMessage) =
        json.encodeToString(PartyClientMessage.serializer(), message)

    private suspend fun WebSocketSession.next(): PartyServerMessage? =
        withTimeoutOrNull(5_000) {
            while (true) {
                val frame = incoming.receive()
                if (frame is Frame.Text) {
                    return@withTimeoutOrNull json.decodeFromString(
                        PartyServerMessage.serializer(),
                        frame.readText(),
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    /**
     * Reads until a message of type [T] satisfying [until] arrives.
     *
     * The predicate matters: connecting already pushes one `State`, so a test
     * waiting for the state *after* an action has to say which one it means.
     */
    private suspend inline fun <reified T : PartyServerMessage> WebSocketSession.await(
        until: (T) -> Boolean = { true },
    ): T? {
        repeat(6) {
            val message = next() ?: return null
            if (message is T && until(message)) return message
        }
        return null
    }

    @Test
    fun `connecting gets the current party state`() = testApplication {
        application { rankedApi(deps) }
        val session = login("Solo")
        val socket = wsClient().webSocketSession("/v1/party?token=${session.token}")

        socket.send(Frame.Text(encode(PartyClientMessage.Hello)))
        val state = assertIs<PartyServerMessage.State>(socket.next())
        assertNull(state.party, "a player in no party has no party")

        socket.close()
    }

    @Test
    fun `an unauthenticated socket is refused`() = testApplication {
        application { rankedApi(deps) }
        val socket = wsClient().webSocketSession("/v1/party?token=nonsense")
        assertIs<PartyServerMessage.Error>(socket.next())
        socket.close()
    }

    @Test
    fun `creating a party pushes the new state back`() = testApplication {
        application { rankedApi(deps) }
        val session = login("Leader")
        val socket = wsClient().webSocketSession("/v1/party?token=${session.token}")

        socket.send(Frame.Text(encode(PartyClientMessage.Create)))
        val state = assertNotNull(
            socket.await<PartyServerMessage.State> { it.party != null },
            "no state with a party pushed after create",
        )
        val party = assertNotNull(state.party)
        assertEquals(session.profile.uuid, party.leader)
        assertEquals(1, party.members.size)

        socket.close()
    }

    @Test
    fun `an invite reaches the invited player's socket`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val client = wsClient()

        val bobSocket = client.webSocketSession("/v1/party?token=${bob.token}")
        bobSocket.send(Frame.Text(encode(PartyClientMessage.Hello)))
        assertIs<PartyServerMessage.State>(bobSocket.next())

        val aliceSocket = client.webSocketSession("/v1/party?token=${alice.token}")
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Create)))
        assertNotNull(aliceSocket.await<PartyServerMessage.State> { it.party != null })
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Invite(bob.profile.uuid))))

        // This is the notification the player is waiting for.
        val invited = assertNotNull(bobSocket.await<PartyServerMessage.Invited>(), "Bob was never told he was invited")
        assertEquals(alice.profile.uuid, invited.invite.from.uuid)
        assertTrue(invited.invite.expiresAt > 0)

        // …and accepting it puts him in the party, on both sockets.
        bobSocket.send(Frame.Text(encode(PartyClientMessage.AcceptInvite(invited.invite.partyId))))
        val bobState = assertNotNull(
            bobSocket.await<PartyServerMessage.State> { it.party != null },
        )
        assertEquals(2, bobState.party?.members?.size)

        aliceSocket.close()
        bobSocket.close()
    }

    @Test
    fun `both players having made their own party still lets one invite the other`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val client = wsClient()

        // Exactly the dead end seen in play: both open the party screen and
        // press create, each landing in a one-person party of their own.
        val bobSocket = client.webSocketSession("/v1/party?token=${bob.token}")
        bobSocket.send(Frame.Text(encode(PartyClientMessage.Create)))
        assertNotNull(bobSocket.await<PartyServerMessage.State> { it.party != null })

        val aliceSocket = client.webSocketSession("/v1/party?token=${alice.token}")
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Create)))
        assertNotNull(aliceSocket.await<PartyServerMessage.State> { it.party != null })

        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Invite(bob.profile.uuid))))

        val invited = assertNotNull(
            bobSocket.await<PartyServerMessage.Invited>(),
            "the invite was refused because Bob was in his own empty party",
        )
        bobSocket.send(Frame.Text(encode(PartyClientMessage.AcceptInvite(invited.invite.partyId))))
        val joined = assertNotNull(
            bobSocket.await<PartyServerMessage.State> { (it.party?.members?.size ?: 0) == 2 },
            "Bob never ended up in Alice's party",
        )
        assertEquals(alice.profile.uuid, joined.party?.leader)

        aliceSocket.close()
        bobSocket.close()
    }

    @Test
    fun `a burst of messages all arrive`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val client = wsClient()

        val bobSocket = client.webSocketSession("/v1/party?token=${bob.token}")
        bobSocket.send(Frame.Text(encode(PartyClientMessage.Hello)))
        assertIs<PartyServerMessage.State>(bobSocket.next())

        // Create and Invite back to back with no wait between them — what a
        // button press does. The second must not be swallowed.
        val aliceSocket = client.webSocketSession("/v1/party?token=${alice.token}")
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Create)))
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Invite(bob.profile.uuid))))

        assertNotNull(bobSocket.await<PartyServerMessage.Invited>(), "the second message was lost")

        aliceSocket.close()
        bobSocket.close()
    }

    @Test
    fun `a refused action answers with an error and changes nothing`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val client = wsClient()

        val aliceSocket = client.webSocketSession("/v1/party?token=${alice.token}")
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Create)))
        assertNotNull(aliceSocket.await<PartyServerMessage.State> { it.party != null })

        // Bob is not in Alice's party, so she cannot kick him.
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Kick(bob.profile.uuid))))
        assertNotNull(aliceSocket.await<PartyServerMessage.Error>(), "no refusal sent")

        aliceSocket.close()
    }

    @Test
    fun `the leader leaving disbands the party for the members`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val client = wsClient()

        val bobSocket = client.webSocketSession("/v1/party?token=${bob.token}")
        bobSocket.send(Frame.Text(encode(PartyClientMessage.Hello)))
        assertIs<PartyServerMessage.State>(bobSocket.next())

        val aliceSocket = client.webSocketSession("/v1/party?token=${alice.token}")
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Create)))
        assertNotNull(aliceSocket.await<PartyServerMessage.State> { it.party != null })
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Invite(bob.profile.uuid))))
        val invited = assertNotNull(bobSocket.await<PartyServerMessage.Invited>())
        bobSocket.send(Frame.Text(encode(PartyClientMessage.AcceptInvite(invited.invite.partyId))))
        assertNotNull(bobSocket.await<PartyServerMessage.State>())

        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Leave)))

        assertNotNull(bobSocket.await<PartyServerMessage.Disbanded>(), "the leader left and Bob was never told")
        aliceSocket.close()
        bobSocket.close()
    }

    /** Alice creates a party, Bob joins it. Returns both sockets, Alice first. */
    private suspend fun ApplicationTestBuilder.partyOfTwo(
        alice: SessionResponse,
        bob: SessionResponse,
    ): Pair<WebSocketSession, WebSocketSession> {
        val client = wsClient()
        val bobSocket = client.webSocketSession("/v1/party?token=${bob.token}")
        bobSocket.send(Frame.Text(encode(PartyClientMessage.Hello)))
        assertIs<PartyServerMessage.State>(bobSocket.next())

        val aliceSocket = client.webSocketSession("/v1/party?token=${alice.token}")
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Create)))
        assertNotNull(aliceSocket.await<PartyServerMessage.State> { it.party != null })
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.Invite(bob.profile.uuid))))
        val invited = assertNotNull(bobSocket.await<PartyServerMessage.Invited>())
        bobSocket.send(Frame.Text(encode(PartyClientMessage.AcceptInvite(invited.invite.partyId))))
        assertNotNull(bobSocket.await<PartyServerMessage.State> { (it.party?.members?.size ?: 0) == 2 })
        return aliceSocket to bobSocket
    }

    @Test
    fun `starting a party-only match tells every member where to connect`() = testApplication {
        // Stands in for the orchestrator: without something answering
        // onMatchCreated the record never leaves PENDING and the start would
        // sit out its whole provisioning timeout.
        matchService.onMatchCreated { record ->
            matchService.setServerAddress(record.id, "party.invalid:25565")
            matchService.markReady(record.id.toString(), record.serverToken)
        }
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val (aliceSocket, bobSocket) = partyOfTwo(alice, bob)

        aliceSocket.send(
            Frame.Text(
                encode(
                    PartyClientMessage.SetOptions(
                        PartyOptions(format = MatchFormat.PARTY_FFA)
                    )
                )
            )
        )
        assertNotNull(
            aliceSocket.await<PartyServerMessage.State> {
                it.party?.options?.format == MatchFormat.PARTY_FFA && it.party?.startBlockedReason == null
            },
            "the party never became startable",
        )

        aliceSocket.send(Frame.Text(encode(PartyClientMessage.StartMatch)))

        // Both members, not only the leader who pressed the button: the member's
        // client has no other way to learn the server address.
        val forAlice = assertNotNull(
            aliceSocket.await<PartyServerMessage.MatchStarting>(),
            "the leader was never told the match started",
        )
        val forBob = assertNotNull(
            bobSocket.await<PartyServerMessage.MatchStarting>(),
            "the member was never told the match started",
        )
        assertEquals(forAlice.match.matchId, forBob.match.matchId, "they were sent to different matches")
        assertEquals("party.invalid:25565", forBob.match.serverAddress)
        assertEquals(MatchFormat.PARTY_FFA, forBob.match.format)

        aliceSocket.close()
        bobSocket.close()
    }

    @Test
    fun `a member cannot start the party's match`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val (aliceSocket, bobSocket) = partyOfTwo(alice, bob)

        bobSocket.send(Frame.Text(encode(PartyClientMessage.StartMatch)))
        val failed = assertNotNull(
            bobSocket.await<PartyServerMessage.StartFailed>(),
            "a member's start was silently ignored",
        )
        assertTrue("leader" in failed.reason, "unhelpful refusal: ${failed.reason}")

        aliceSocket.close()
        bobSocket.close()
    }

    @Test
    fun `an open-queue format is not startable from the party socket`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Alice")
        val bob = login("Bob")
        val (aliceSocket, bobSocket) = partyOfTwo(alice, bob)

        // RANKED_2V2 needs an opponent, which only the matchmaker can find.
        aliceSocket.send(
            Frame.Text(
                encode(PartyClientMessage.SetOptions(PartyOptions(format = MatchFormat.RANKED_2V2)))
            )
        )
        assertNotNull(
            aliceSocket.await<PartyServerMessage.State> { it.party?.options?.format == MatchFormat.RANKED_2V2 }
        )
        aliceSocket.send(Frame.Text(encode(PartyClientMessage.StartMatch)))

        val failed = assertNotNull(
            aliceSocket.await<PartyServerMessage.StartFailed>(),
            "an open-queue format was accepted as a party start",
        )
        assertTrue("queue" in failed.reason, "unhelpful refusal: ${failed.reason}")

        aliceSocket.close()
        bobSocket.close()
    }

    @Test
    fun `starting with nobody else in the party is refused`() = testApplication {
        application { rankedApi(deps) }
        val alice = login("Solo")
        val socket = wsClient().webSocketSession("/v1/party?token=${alice.token}")
        socket.send(Frame.Text(encode(PartyClientMessage.Create)))
        assertNotNull(socket.await<PartyServerMessage.State> { it.party != null })
        socket.send(
            Frame.Text(encode(PartyClientMessage.SetOptions(PartyOptions(format = MatchFormat.PARTY_FFA))))
        )
        assertNotNull(socket.await<PartyServerMessage.State> { it.party?.options?.format == MatchFormat.PARTY_FFA })

        socket.send(Frame.Text(encode(PartyClientMessage.StartMatch)))
        assertNotNull(
            socket.await<PartyServerMessage.StartFailed>(),
            "a one-player party was allowed to start a match against itself",
        )
        assertNull(
            matches.historyFor(UUID.fromString(alice.profile.uuid), season = 1, limit = 10).firstOrNull(),
            "a match record was written for a party that could not start",
        )

        socket.close()
    }
}
