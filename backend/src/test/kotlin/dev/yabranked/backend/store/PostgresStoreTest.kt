package dev.yabranked.backend.store

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip test against a real Postgres in Docker (postgres:16-alpine on
 * port 55432). Skipped when Docker is not available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PostgresStoreTest {

    private val containerName = "yabranked-test-pg"
    private var dockerAvailable = false
    private lateinit var db: Database

    private fun sh(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)
        return process.exitValue() to out
    }

    @BeforeAll
    fun startPostgres() {
        dockerAvailable = sh("docker", "info").first == 0
        if (!dockerAvailable) return

        sh("docker", "rm", "-f", containerName)
        val (exit, out) = sh(
            "docker", "run", "-d", "--name", containerName,
            "-e", "POSTGRES_PASSWORD=test", "-e", "POSTGRES_DB=yabranked",
            "-p", "55432:5432", "postgres:16-alpine",
        )
        check(exit == 0) { "could not start postgres container: $out" }

        // wait for postgres to accept connections
        val deadline = System.currentTimeMillis() + 60_000
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                db = Database("jdbc:postgresql://localhost:55432/yabranked", "postgres", "test")
                db.migrate()
                return
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(1000)
            }
        }
        throw IllegalStateException("postgres did not become ready", lastError)
    }

    @AfterAll
    fun stopPostgres() {
        if (dockerAvailable) sh("docker", "rm", "-f", containerName)
    }

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @Test
    fun `player, stats, match, report, and settings round-trip`() {
        assumeTrue(dockerAvailable, "docker not available")

        val players = PostgresPlayerStore(db)
        val matches = PostgresMatchStore(db)
        val reports = PostgresReportStore(db)
        val settings = PostgresSettingsStore(db)

        // players + ban flag
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        players.upsertPlayer(PlayerRecord(a, "Anna", createdAt = now()))
        players.upsertPlayer(PlayerRecord(b, "Ben", createdAt = now()))
        assertEquals("Anna", players.getPlayer(a)!!.name)
        players.upsertPlayer(players.getPlayer(a)!!.copy(bannedAt = now()))
        assertTrue(players.getPlayer(a)!!.isBanned)
        players.upsertPlayer(players.getPlayer(a)!!.copy(bannedAt = null))

        // season stats + leaderboard + rank
        players.upsertStats(SeasonStats(a, season = 1, rating = 1040, matchesPlayed = 1, wins = 1, losses = 0, draws = 0))
        players.upsertStats(SeasonStats(b, season = 1, rating = 960, matchesPlayed = 1, wins = 0, losses = 1, draws = 0))
        val top = players.topByRating(season = 1, limit = 10, minMatches = 1)
        assertEquals(listOf(a, b), top.map { it.uuid })
        assertEquals(1, players.rankOf(a, season = 1, minMatches = 1))
        assertEquals(2, players.rankOf(b, season = 1, minMatches = 1))
        assertNull(players.rankOf(UUID.randomUUID(), season = 1, minMatches = 1))
        // other season is a fresh ladder
        assertNull(players.getStats(a, season = 2))

        // match insert/update/history
        val match = MatchRecord(
            id = UUID.randomUUID(),
            season = 1,
            format = MatchFormat.LOCKOUT_1V1,
            settings = MatchSettings(MatchFormat.LOCKOUT_1V1, worldSeed = 42L, cardSeed = 7L, timeLimitSeconds = 5400),
            playerA = a,
            playerB = b,
            status = MatchStatus.PENDING,
            serverToken = "token",
            outcome = null,
            ratingABefore = 1000,
            ratingBBefore = 1000,
            ratingAAfter = null,
            ratingBAfter = null,
            createdAt = now(),
            completedAt = null,
        )
        matches.insert(match)
        assertEquals(match.settings, matches.get(match.id)!!.settings)

        matches.update(
            match.copy(
                status = MatchStatus.COMPLETED,
                serverAddress = "host:25600",
                outcome = MatchOutcome.TEAM_A_WIN,
                ratingAAfter = 1040,
                ratingBAfter = 960,
                durationSeconds = 777,
                teamAScore = 13,
                teamBScore = 9,
                completedAt = now(),
            )
        )
        val loaded = matches.get(match.id)!!
        assertEquals(MatchStatus.COMPLETED, loaded.status)
        assertEquals(MatchOutcome.TEAM_A_WIN, loaded.outcome)
        assertEquals(777, loaded.durationSeconds)
        assertEquals(13, loaded.teamAScore)
        assertEquals(1, matches.historyFor(a, season = 1, limit = 10).size)
        assertEquals(0, matches.historyFor(a, season = 2, limit = 10).size)

        // reports
        val report = ReportRecord(UUID.randomUUID(), match.id, reporter = a, accused = b, reason = "test", createdAt = now())
        reports.insert(report)
        assertTrue(reports.existsFor(match.id, a))
        assertEquals(1, reports.list(10).size)

        // settings
        settings.put("current_season", "3")
        settings.put("current_season", "4")
        assertEquals("4", settings.get("current_season"))
        assertNull(settings.get("missing"))
    }

    /**
     * The report paths that only Postgres has, and the one that was broken.
     *
     * `existsFor(match, reporter, accused)` has allowed one report per accused
     * since team formats landed, but the baseline schema still carried
     * `UNIQUE (match_id, reporter)` — so reporting a second opponent in a 4v4
     * passed the application's check and then died on the insert. Every test
     * ran against the in-memory store, which has no constraint, so nothing
     * caught it. Migration V6 replaces the constraint; this is what says so.
     */
    @Test
    fun `reports allow one row per accused, and carry a moderator's decision`() {
        assumeTrue(dockerAvailable, "docker not available")

        val players = PostgresPlayerStore(db)
        val matches = PostgresMatchStore(db)
        val reports = PostgresReportStore(db)

        val reporter = UUID.randomUUID()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        listOf(reporter to "Rep", first to "One", second to "Two").forEach { (uuid, name) ->
            players.upsertPlayer(PlayerRecord(uuid, name, createdAt = now()))
        }
        val match = MatchRecord(
            id = UUID.randomUUID(),
            season = 1,
            format = MatchFormat.RANKED_2V2,
            settings = MatchSettings(MatchFormat.RANKED_2V2, worldSeed = 1L, cardSeed = 2L, timeLimitSeconds = 5400),
            playerA = reporter,
            playerB = first,
            status = MatchStatus.COMPLETED,
            serverToken = "token",
            outcome = MatchOutcome.TEAM_A_WIN,
            ratingABefore = 1000,
            ratingBBefore = 1000,
            ratingAAfter = 1040,
            ratingBAfter = 960,
            createdAt = now(),
            completedAt = now(),
            teams = listOf(listOf(reporter), listOf(first, second)),
        )
        matches.insert(match)

        // Two opponents, two reports, one reporter — this is the insert that
        // used to violate reports_match_id_reporter_key.
        val one = ReportRecord(UUID.randomUUID(), match.id, reporter, first, "cheating", now())
        val two = ReportRecord(UUID.randomUUID(), match.id, reporter, second, "also cheating", now())
        reports.insert(one)
        reports.insert(two)
        assertTrue(reports.existsFor(match.id, reporter, first))
        assertTrue(reports.existsFor(match.id, reporter, second))
        assertEquals(2, reports.forMatch(match.id).size)

        // fresh rows are OPEN with nothing recorded against them
        val loaded = reports.get(one.id)!!
        assertEquals(ReportStatus.OPEN, loaded.status)
        assertNull(loaded.resolvedAt)
        assertEquals(2, reports.list(50, ReportStatus.OPEN).count { it.matchId == match.id })

        val decided = reports.resolve(one.id, ReportStatus.ACTIONED, "ovilli", "confirmed", now())!!
        assertEquals(ReportStatus.ACTIONED, decided.status)
        assertEquals("ovilli", decided.resolvedBy)
        assertEquals("confirmed", decided.resolutionNote)
        assertTrue(decided.resolvedAt != null)
        assertEquals(decided, reports.get(one.id), "the decision must survive the round-trip")

        // COALESCE: a later transition that names nobody keeps who decided it
        val again = reports.resolve(one.id, ReportStatus.DISMISSED, null, null, now())!!
        assertEquals("ovilli", again.resolvedBy)
        assertEquals("confirmed", again.resolutionNote)

        // REVIEWING is a claim, not a decision, so it clears the timestamp again
        assertNull(reports.resolve(two.id, ReportStatus.REVIEWING, "ovilli", null, now())!!.resolvedAt)

        assertNull(reports.resolve(UUID.randomUUID(), ReportStatus.DISMISSED, null, null, now()))

        val counts = reports.countsAgainst(listOf(first, second, UUID.randomUUID()))
        assertEquals(1, counts[first])
        assertEquals(1, counts[second])
        assertEquals(2, counts.size, "an account with no reports must not appear with a zero")
        assertTrue(reports.countsAgainst(emptyList()).isEmpty())
    }
}
