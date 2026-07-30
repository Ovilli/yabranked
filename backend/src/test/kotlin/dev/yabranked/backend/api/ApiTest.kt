package dev.yabranked.backend.api

import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.PlayerProfile
import dev.yabranked.proto.QueueServerMessage
import dev.yabranked.proto.SessionRequest
import dev.yabranked.proto.SessionResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
    private val queueService = QueueService(MatchmakingQueue(), matchService)

    private fun deps() = ApiDependencies(
        verifier = FakeSessionVerifier(),
        players = players,
        matches = matches,
        matchService = matchService,
        queueService = queueService,
    )

    @Test
    fun `auth issues token and creates player`() = testApplication {
        application { rankedApi(deps()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val response = client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("TestPlayer", "serverid"))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val session: SessionResponse = response.body()
        assertTrue(session.token.isNotBlank())
        assertEquals("TestPlayer", session.profile.name)
        assertEquals(5, session.profile.placementMatchesRemaining)

        val profile: PlayerProfile = client.get("/v1/players/${session.profile.uuid}").body()
        assertEquals(session.profile, profile)
    }

    @Test
    fun `result endpoint settles a match and leaderboard updates`() = testApplication {
        application { rankedApi(deps()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        matchService.getOrCreatePlayer(a, "Anna")
        matchService.getOrCreatePlayer(b, "Ben")

        fun newMatch() = matchService.createMatch(
            QueueMatch(
                QueueEntry(a, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(b, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )

        // Only placed players are ranked, so get both past placements before
        // the leaderboard assertion below can say anything.
        repeat(matchService.placementMatches - 1) {
            val filler = newMatch()
            matchService.settle(
                MatchResultReport(filler.id.toString(), MatchOutcome.TEAM_A_WIN, 700, 11, 3),
                filler.serverToken,
            )
        }

        val match = newMatch()
        val report = MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 700, 11, 3)

        // wrong token -> 401
        val unauthorized = client.post("/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer nope")
            setBody(report)
        }
        assertEquals(HttpStatusCode.Unauthorized, unauthorized.status)

        // correct token -> settled
        val ok = client.post("/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${match.serverToken}")
            setBody(report)
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        // second report -> conflict
        val again = client.post("/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${match.serverToken}")
            setBody(report)
        }
        assertEquals(HttpStatusCode.Conflict, again.status)

        val leaderboard: List<PlayerProfile> = client.get("/v1/leaderboard").body()
        assertEquals(2, leaderboard.size)
        assertEquals("Anna", leaderboard[0].name)
        assertTrue(leaderboard[0].rating > leaderboard[1].rating)
    }

    @Test
    fun `the live-match endpoint tracks a match from created to settled`() = testApplication {
        application { rankedApi(deps()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        suspend fun login(name: String): SessionResponse = client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest(name, "serverid-$name"))
        }.body()

        val anna = login("Anna")
        val ben = login("Ben")
        val bystander = login("Bystander")
        suspend fun liveFor(session: SessionResponse) = client.get("/v1/players/me/match") {
            header("Authorization", "Bearer ${session.token}")
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/players/me/match").status)
        assertEquals(HttpStatusCode.NoContent, liveFor(anna).status, "no match, no content")

        val match = matchService.createMatch(
            QueueMatch(
                QueueEntry(UUID.fromString(anna.profile.uuid), 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(UUID.fromString(ben.profile.uuid), 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )

        // Both sides see it; nobody else does.
        for (session in listOf(anna, ben)) {
            val response = liveFor(session)
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(match.id.toString(), response.body<QueueServerMessage.MatchFound>().matchId)
        }
        assertEquals(HttpStatusCode.NoContent, liveFor(bystander).status)

        // Ben concedes without ever having connected to the match server — the
        // exact shape that leaves Anna's client believing the match is still on.
        matchService.forfeit(match.id, UUID.fromString(ben.profile.uuid))

        assertEquals(
            HttpStatusCode.NoContent,
            liveFor(anna).status,
            "the winner's client is never told the match is over",
        )
        assertEquals(HttpStatusCode.NoContent, liveFor(ben).status)
    }

    @Test
    fun `a player can forfeit their own match with only their session token`() = testApplication {
        application { rankedApi(deps()) }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        suspend fun login(name: String): SessionResponse = client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest(name, "serverid-$name"))
        }.body()

        val anna = login("Anna")
        val ben = login("Ben")
        val stranger = login("Stranger")
        val match = matchService.createMatch(
            QueueMatch(
                QueueEntry(UUID.fromString(anna.profile.uuid), 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(UUID.fromString(ben.profile.uuid), 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )

        // No token at all: this is the endpoint that ends ranked matches.
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.post("/v1/matches/${match.id}/forfeit").status,
        )

        // Someone else's match is indistinguishable from one that does not exist.
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/v1/matches/${match.id}/forfeit") {
                header("Authorization", "Bearer ${stranger.token}")
            }.status,
        )

        // The whole point: no per-match server token, and the match settles.
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/matches/${match.id}/forfeit") {
                header("Authorization", "Bearer ${anna.token}")
            }.status,
        )
        assertEquals(MatchOutcome.TEAM_B_WIN, matches.get(match.id)?.outcome)

        // Pressing it twice, or racing the agent's own report, is not an error
        // the player should ever have to think about.
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/v1/matches/${match.id}/forfeit") {
                header("Authorization", "Bearer ${anna.token}")
            }.status,
        )
    }
}
