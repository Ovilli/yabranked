package dev.yabranked.backend.api

import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.PlayerProfile
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
    private val matchService = MatchService(players, matches, EloRatingSystem())
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
        val match = matchService.createMatch(
            QueueMatch(
                QueueEntry(a, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(b, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )

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
}
