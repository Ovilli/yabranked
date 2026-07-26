package dev.yabranked.backend.api

import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.security.RateLimiter
import dev.yabranked.backend.security.RateLimiters
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.SessionRequest
import dev.yabranked.proto.SessionResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * The abuse surfaces: unlimited session minting proxied to Mojang, tokens that
 * outlive a ban, and an agent report that is trusted whatever it says.
 */
class SecurityTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
    private val queueService = QueueService(MatchmakingQueue(), matchService)

    private fun deps(
        tokens: TokenRegistry = TokenRegistry(),
        rateLimits: RateLimiters = RateLimiters(),
        adminToken: String? = "admin-secret",
    ) = ApiDependencies(
        verifier = FakeSessionVerifier(),
        players = players,
        matches = matches,
        matchService = matchService,
        queueService = queueService,
        tokens = tokens,
        rateLimits = rateLimits,
        adminToken = adminToken,
    )

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    private suspend fun ApplicationTestBuilder.login(name: String): SessionResponse =
        jsonClient().post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest(name, "serverid-$name"))
        }.body()

    private fun newMatch(): dev.yabranked.backend.store.MatchRecord {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        matchService.getOrCreatePlayer(a, "A${a.toString().take(4)}")
        matchService.getOrCreatePlayer(b, "B${b.toString().take(4)}")
        return matchService.createMatch(
            QueueMatch(
                QueueEntry(a, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(b, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )
    }

    @Test
    fun `session minting is capped per source address`() = testApplication {
        // without this the endpoint is an amplifier: every call is proxied to
        // Mojang's hasJoined on the deployment's behalf
        application {
            rankedApi(deps(rateLimits = RateLimiters(session = RateLimiter(limit = 3, window = 1.minutes))))
        }
        val client = jsonClient()

        repeat(3) {
            val response = client.post("/v1/auth/session") {
                contentType(ContentType.Application.Json)
                setBody(SessionRequest("Player$it", "serverid-$it"))
            }
            assertEquals(HttpStatusCode.OK, response.status, "call ${it + 1} should be allowed")
        }

        val refused = client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("PlayerN", "serverid-n"))
        }
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertNotNull(refused.headers["Retry-After"], "a throttled caller needs to know when to come back")
    }

    @Test
    fun `guessing a server token is capped`() = testApplication {
        application {
            rankedApi(deps(rateLimits = RateLimiters(internal = RateLimiter(limit = 2, window = 1.minutes))))
        }
        val client = jsonClient()
        val match = newMatch()
        val report = MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5)

        repeat(2) {
            val response = client.post("/v1/internal/matches/result") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer guess-$it")
                setBody(report)
            }
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

        val refused = client.post("/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer guess-again")
            setBody(report)
        }
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
    }

    @Test
    fun `an expired token stops resolving and is swept`() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun instant() = now
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
        }
        val tokens = TokenRegistry(ttl = 1.hours, clock = clock)
        val player = UUID.randomUUID()
        val token = tokens.issue(player)
        assertEquals(player, tokens.resolve(token))

        now = now.plus(Duration.ofHours(2))

        assertNull(tokens.resolve(token), "an expired session must not authenticate")
        // issuing sweeps, so a registry fed forever does not grow forever
        tokens.issue(UUID.randomUUID())
        assertEquals(1, tokens.size)
    }

    @Test
    fun `banning a player invalidates the sessions they already hold`() = testApplication {
        val tokens = TokenRegistry()
        application { rankedApi(deps(tokens = tokens)) }
        val client = jsonClient()
        val session = login("Cheater")
        val uuid = UUID.fromString(session.profile.uuid)
        assertEquals(uuid, tokens.resolve(session.token))

        val banned = client.post("/v1/admin/bans/$uuid") {
            header("X-Admin-Token", "admin-secret")
        }
        assertEquals(HttpStatusCode.OK, banned.status)

        // the whole point: a ban used to do nothing to someone already signed in
        assertNull(tokens.resolve(session.token), "the banned player's live session still works")
    }

    @Test
    fun `admin endpoints refuse a wrong or missing token`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()
        val uuid = UUID.randomUUID()

        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/v1/admin/bans/$uuid").status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            client.post("/v1/admin/bans/$uuid") { header("X-Admin-Token", "wrong") }.status,
        )
    }

    @Test
    fun `admin endpoints stay shut when no admin token is configured`() = testApplication {
        // a deployment that never set one must not be administrable by anyone
        application { rankedApi(deps(adminToken = null)) }

        val response = jsonClient().post("/v1/admin/bans/${UUID.randomUUID()}") {
            header("X-Admin-Token", "anything")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `a report with an absurd duration is rejected instead of banked as playtime`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()
        val match = newMatch()

        val response = client.post("/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${match.serverToken}")
            setBody(MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, -5, 10, 5))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(0, matchService.statsFor(match.playerA).matchesPlayed, "the bad report was applied")
    }

    @Test
    fun `a forfeitedBy who is not in the match is rejected`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()
        val match = newMatch()

        val response = client.post("/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${match.serverToken}")
            setBody(
                MatchResultReport(
                    match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5,
                    forfeitedBy = UUID.randomUUID().toString(),
                )
            )
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `malformed json is a 400, not a 500 with a stack trace`() = testApplication {
        application { rankedApi(deps()) }

        val response = jsonClient().post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody("{ this is not json")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `the queue socket authenticates from the Authorization header`() = testApplication {
        // the query-string form copies the token into every access and proxy log
        application { rankedApi(deps()) }
        val session = login("HeaderUser")
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val socket = client.webSocketSession("/v1/queue") {
            header("Authorization", "Bearer ${session.token}")
        }
        socket.send(
            io.ktor.websocket.Frame.Text(
                Json { classDiscriminator = "type" }.encodeToString(
                    dev.yabranked.proto.QueueClientMessage.serializer(),
                    dev.yabranked.proto.QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1),
                )
            )
        )

        val message = socket.nextQueueMessage()
        assertTrue(
            message is dev.yabranked.proto.QueueServerMessage.QueueState,
            "header auth was refused: $message",
        )
        socket.close()
    }

    @Test
    fun `the queue socket refuses a query-string token once the compatibility flag is off`() = testApplication {
        application {
            rankedApi(
                ApiDependencies(
                    verifier = FakeSessionVerifier(),
                    players = players,
                    matches = matches,
                    matchService = matchService,
                    queueService = queueService,
                    allowQueryToken = false,
                )
            )
        }
        val session = login("QueryUser")
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(io.ktor.client.plugins.websocket.WebSockets)
        }

        val socket = client.webSocketSession("/v1/queue?token=${session.token}")

        val message = socket.nextQueueMessage()
        assertTrue(
            message is dev.yabranked.proto.QueueServerMessage.QueueError,
            "the query-string token was still accepted: $message",
        )
        socket.close()
    }
}

private suspend fun io.ktor.websocket.WebSocketSession.nextQueueMessage():
    dev.yabranked.proto.QueueServerMessage? =
    kotlinx.coroutines.withTimeoutOrNull(5_000) {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        while (true) {
            val frame = incoming.receive()
            if (frame is io.ktor.websocket.Frame.Text) {
                return@withTimeoutOrNull json.decodeFromString(
                    dev.yabranked.proto.QueueServerMessage.serializer(),
                    frame.readText(),
                )
            }
        }
        @Suppress("UNREACHABLE_CODE") null
    }
