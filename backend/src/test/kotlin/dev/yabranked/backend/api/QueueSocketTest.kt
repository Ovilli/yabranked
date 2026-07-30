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
import dev.yabranked.proto.QueueClientMessage
import dev.yabranked.proto.QueueServerMessage
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
import io.ktor.websocket.close
import kotlinx.coroutines.cancel
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the queue WebSocket: it owns the player's queue entry, so getting the
 * ownership rules wrong dequeues the wrong session.
 */
class QueueSocketTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
    private val queueService = QueueService(MatchmakingQueue(), matchService)
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private fun deps() = ApiDependencies(
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

    private fun encode(message: QueueClientMessage) =
        json.encodeToString(QueueClientMessage.serializer(), message)

    private suspend fun io.ktor.websocket.WebSocketSession.nextMessage(): QueueServerMessage? =
        withTimeoutOrNull(5_000) {
            while (true) {
                val frame = incoming.receive()
                if (frame is Frame.Text) {
                    return@withTimeoutOrNull json.decodeFromString(
                        QueueServerMessage.serializer(),
                        frame.readText(),
                    )
                }
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    @Test
    fun `a second socket for the same player is rejected and leaves the first queued`() = testApplication {
        application { rankedApi(deps()) }
        val session = login("Duplicate")
        val uuid = UUID.fromString(session.profile.uuid)

        val first = wsClient().webSocketSession("/v1/queue?token=${session.token}")
        first.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        assertTrue(first.nextMessage() is QueueServerMessage.QueueState, "first socket never got queue state")

        val second = wsClient().webSocketSession("/v1/queue?token=${session.token}")
        second.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        val rejection = second.nextMessage()
        assertTrue(rejection is QueueServerMessage.QueueError, "duplicate socket was not rejected: $rejection")
        second.close()

        // the rejected socket must not have dequeued the session that owns the entry
        assertNotNull(queueService.snapshot(uuid), "the first socket was dequeued by the second one closing")
        assertTrue(first.nextMessage() is QueueServerMessage.QueueState, "first socket stopped receiving state")
        first.close()
    }

    @Test
    fun `leave_queue dequeues without closing the socket first`() = testApplication {
        application { rankedApi(deps()) }
        val session = login("Leaver")
        val uuid = UUID.fromString(session.profile.uuid)

        val socket = wsClient().webSocketSession("/v1/queue?token=${session.token}")
        socket.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        assertTrue(socket.nextMessage() is QueueServerMessage.QueueState)
        assertNotNull(queueService.snapshot(uuid))

        socket.send(Frame.Text(encode(QueueClientMessage.LeaveQueue)))

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && queueService.snapshot(uuid) != null) {
            kotlinx.coroutines.delay(50)
        }
        assertNull(queueService.snapshot(uuid), "leave_queue was never acted on")
        socket.close()
    }

    @Test
    fun `a frame we cannot decode does not deafen the socket to leave_queue`() = testApplication {
        // The reader coroutine is the only thing that ever sees leave_queue.
        // Falling out of it on an undecodable frame left the client queued with
        // a Cancel button that did nothing, while the push loop kept the socket
        // open and the timer ticking — the client had no way to tell.
        application { rankedApi(deps()) }
        val session = login("Garbler")
        val uuid = UUID.fromString(session.profile.uuid)

        val socket = wsClient().webSocketSession("/v1/queue?token=${session.token}")
        socket.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        assertTrue(socket.nextMessage() is QueueServerMessage.QueueState)
        assertNotNull(queueService.snapshot(uuid))

        // anything this build's QueueClientMessage cannot decode: a newer
        // client's message type, or simply a bad frame
        socket.send(Frame.Text("""{"type":"from_a_newer_client"}"""))
        socket.send(Frame.Text(encode(QueueClientMessage.LeaveQueue)))

        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && queueService.snapshot(uuid) != null) {
            kotlinx.coroutines.delay(50)
        }
        assertNull(queueService.snapshot(uuid), "leave_queue after a bad frame was never read")
        socket.close()
    }

    @Test
    fun `a paired player keeps hearing from us while the server boots`() = testApplication {
        // Pairing dequeues the player, so the state pushes used to stop dead and
        // the client rendered "searching" with a frozen timer for the whole
        // minute the container took to boot.
        application { rankedApi(deps()) }
        val alice = login("Alice")
        val bob = login("Bob")

        val socket = wsClient().webSocketSession("/v1/queue?token=${alice.token}")
        socket.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        assertTrue(socket.nextMessage() is QueueServerMessage.QueueState)

        // second player pairs with the first; no orchestrator here, so the match
        // stays PENDING exactly like a container that has not reported ready
        val other = wsClient().webSocketSession("/v1/queue?token=${bob.token}")
        other.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        assertTrue(other.nextMessage() is QueueServerMessage.QueueState, "second socket never queued")

        val bobUuid = UUID.fromString(bob.profile.uuid)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && queueService.snapshot(bobUuid) == null) {
            kotlinx.coroutines.delay(20)
        }
        queueService.tickOnce()

        var preparing: QueueServerMessage.QueueState? = null
        for (attempt in 0 until 10) {
            val message = socket.nextMessage()
            if (message is QueueServerMessage.QueueState && message.preparingMatch) {
                preparing = message
                break
            }
        }

        assertNotNull(preparing, "the client was told nothing while the match server was provisioning")
        other.close()
        socket.close()
    }

    @Test
    fun `a lone player keeps their socket and their queue entry across ticks`() = testApplication {
        application { rankedApi(deps()) }
        val session = login("Patient")
        val client = wsClient()

        val socket = client.webSocketSession("/v1/queue?token=${session.token}")
        socket.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))

        // Nobody to match with, so the socket's whole job is to keep saying so.
        // Three consecutive ticks is enough to catch a handler that hangs up
        // after the first one.
        repeat(3) { tick ->
            val message = socket.nextMessage()
            assertTrue(
                message is QueueServerMessage.QueueState,
                "tick $tick: expected queue state, got $message",
            )
        }
        assertTrue(queueService.snapshot(UUID.fromString(session.profile.uuid)) != null)
        socket.close()
    }

    @Test
    fun `a dropped socket releases the queue entry so the player can reconnect`() = testApplication {
        application { rankedApi(deps()) }
        val session = login("Flaky")
        val uuid = UUID.fromString(session.profile.uuid)
        val client = wsClient()

        // Drop the socket the way a real client does — abruptly, mid-queue.
        val first = client.webSocketSession("/v1/queue?token=${session.token}")
        first.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        assertTrue(first.nextMessage() is QueueServerMessage.QueueState)
        first.cancel()

        // Cleanup runs in the handler's `finally`, which is reached while the
        // coroutine is already cancelled, so every suspend call in it has to be
        // NonCancellable to run at all.
        var released = false
        repeat(50) {
            if (queueService.snapshot(uuid) == null) {
                released = true
                return@repeat
            }
            kotlinx.coroutines.delay(100)
        }
        assertTrue(released, "a dropped socket must not leave the player queued")

        // …and the reconnect that follows is accepted rather than refused as a
        // duplicate session, which is what turned this into a reconnect loop.
        val second = client.webSocketSession("/v1/queue?token=${session.token}")
        second.send(Frame.Text(encode(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))))
        val message = second.nextMessage()
        assertTrue(message is QueueServerMessage.QueueState, "reconnect was refused: $message")
        second.close()
    }

    @Test
    fun `an unauthenticated socket is refused`() = testApplication {
        application { rankedApi(deps()) }

        val socket = wsClient().webSocketSession("/v1/queue?token=not-a-token")
        val message = socket.nextMessage()
        assertTrue(message is QueueServerMessage.QueueError, "expected an error, got $message")
        assertEquals("unauthorized", message.message)
        socket.close()
    }
}
