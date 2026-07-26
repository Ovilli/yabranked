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
import dev.yabranked.backend.store.InMemoryReportStore
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchHistoryEntry
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.ReportRequest
import dev.yabranked.proto.SessionRequest
import dev.yabranked.proto.SessionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
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

class LadderApiTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val seasons = SeasonService()
    private val matchService = MatchService(players, matches, EloRatingSystem(), seasons)
    private val queueService = QueueService(MatchmakingQueue(), matchService)
    private val tokens = TokenRegistry()

    private fun deps() = ApiDependencies(
        verifier = FakeSessionVerifier(),
        players = players,
        matches = matches,
        matchService = matchService,
        queueService = queueService,
        tokens = tokens,
        seasons = seasons,
        reports = InMemoryReportStore(),
        adminToken = "admin-secret",
    )

    private fun playedMatch(a: UUID, b: UUID): dev.yabranked.backend.store.MatchRecord {
        matchService.getOrCreatePlayer(a, "Anna")
        matchService.getOrCreatePlayer(b, "Ben")
        val match = matchService.createMatch(
            QueueMatch(
                QueueEntry(a, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(b, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )
        matchService.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 700, 13, 8),
            match.serverToken,
        )
        return match
    }

    /**
     * Enough wins for [a] to clear placements, since only placed players are
     * ranked. Returns the last match played.
     */
    private fun placedPlayer(a: UUID, b: UUID): dev.yabranked.backend.store.MatchRecord {
        var last = playedMatch(a, b)
        repeat(matchService.placementMatches - 1) { last = playedMatch(a, b) }
        return last
    }

    @Test
    fun `the leaderboard lists only placed players and says so honestly`() = testApplication {
        // It used to query with minMatches=1 while hardcoding isPlaced=true, so
        // one lucky win could show as rank 1 wearing a tier it had not earned.
        application { rankedApi(deps()) }
        val client = jsonClient()

        playedMatch(UUID.randomUUID(), UUID.randomUUID())
        assertEquals("[]", client.get("/v1/leaderboard").body<String>().trim(),
            "a player mid-placement was ranked against established players")

        val a = UUID.randomUUID()
        placedPlayer(a, UUID.randomUUID())

        val ladder: List<dev.yabranked.proto.PlayerProfile> = client.get("/v1/leaderboard").body()
        val anna = ladder.single { it.uuid == a.toString() }
        assertEquals(0, anna.placementMatchesRemaining)
        assertTrue(anna.tier != "Unranked", "a placed player should carry a real tier, got ${anna.tier}")
    }

    @Test
    fun `the leaderboard and the profile agree about a player's tier`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()
        val a = UUID.randomUUID()
        placedPlayer(a, UUID.randomUUID())

        val ladder: List<dev.yabranked.proto.PlayerProfile> = client.get("/v1/leaderboard").body()
        val fromLadder = ladder.single { it.uuid == a.toString() }
        val fromProfile: dev.yabranked.proto.PlayerProfile = client.get("/v1/players/$a").body()

        assertEquals(fromProfile.tier, fromLadder.tier)
        assertEquals(fromProfile.placementMatchesRemaining, fromLadder.placementMatchesRemaining)
    }

    @Test
    fun `profile carries tier and season, history reflects the match`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()

        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        playedMatch(a, b)

        val history: List<MatchHistoryEntry> = client.get("/v1/players/$a/matches").body()
        assertEquals(1, history.size)
        assertEquals("win", history[0].result)
        assertEquals("Ben", history[0].opponent.name)
        assertEquals(1000, history[0].ratingBefore)
        assertEquals(1040, history[0].ratingAfter)
        assertEquals(700, history[0].durationSeconds)

        val bHistory: List<MatchHistoryEntry> = client.get("/v1/players/$b/matches").body()
        assertEquals("loss", bHistory[0].result)
    }

    @Test
    fun `report flow and admin listing`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()

        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val match = playedMatch(a, b)
        val tokenA = tokens.issue(a)

        // unauthenticated -> 401
        val anon = client.post("/v1/reports") {
            contentType(ContentType.Application.Json)
            setBody(ReportRequest(match.id.toString(), "cheating"))
        }
        assertEquals(HttpStatusCode.Unauthorized, anon.status)

        // reporter must have played the match
        val outsider = tokens.issue(UUID.randomUUID())
        val notYours = client.post("/v1/reports") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $outsider")
            setBody(ReportRequest(match.id.toString(), "cheating"))
        }
        assertEquals(HttpStatusCode.NotFound, notYours.status)

        val ok = client.post("/v1/reports") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $tokenA")
            setBody(ReportRequest(match.id.toString(), "cheating"))
        }
        assertEquals(HttpStatusCode.OK, ok.status)

        // duplicate -> conflict
        val dupe = client.post("/v1/reports") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $tokenA")
            setBody(ReportRequest(match.id.toString(), "still cheating"))
        }
        assertEquals(HttpStatusCode.Conflict, dupe.status)

        // admin listing requires the token
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/reports").status)
        val listed = client.get("/v1/admin/reports") { header("X-Admin-Token", "admin-secret") }
        assertEquals(HttpStatusCode.OK, listed.status)
        assertTrue(listed.body<String>().contains(b.toString()), "accused should be the opponent")
    }

    @Test
    fun `ban blocks auth and unban restores it`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()

        // create the account via fake auth
        val first = client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("BadActor", "sid"))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val uuid = first.body<SessionResponse>().profile.uuid

        val ban = client.post("/v1/admin/bans/$uuid") { header("X-Admin-Token", "admin-secret") }
        assertEquals(HttpStatusCode.OK, ban.status)

        val blocked = client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("BadActor", "sid"))
        }
        assertEquals(HttpStatusCode.Forbidden, blocked.status)

        val unban = client.delete("/v1/admin/bans/$uuid") { header("X-Admin-Token", "admin-secret") }
        assertEquals(HttpStatusCode.OK, unban.status)

        val restored = client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest("BadActor", "sid"))
        }
        assertEquals(HttpStatusCode.OK, restored.status)
    }

    @Test
    fun `season advance resets the leaderboard`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()

        placedPlayer(UUID.randomUUID(), UUID.randomUUID())
        assertTrue(client.get("/v1/leaderboard").body<String>().contains("Anna"))

        // no admin token -> forbidden
        assertEquals(HttpStatusCode.Forbidden, client.post("/v1/admin/seasons/advance").status)

        val advanced = client.post("/v1/admin/seasons/advance") { header("X-Admin-Token", "admin-secret") }
        assertEquals(HttpStatusCode.OK, advanced.status)
        assertEquals(2, seasons.currentSeason)

        // The rollover seeds carried ratings but zeroes their match counts, so
        // nobody is placed in the new season until they play it.
        assertEquals("[]", client.get("/v1/leaderboard").body<String>().trim())
        // previous season remains queryable as its final standings
        assertTrue(client.get("/v1/leaderboard?season=1").body<String>().contains("Anna"))
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient(): HttpClient =
        createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
}
