package dev.yabranked.backend.match

import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryAchievementStore
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatchServiceTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val seasons = SeasonService()
    private val achievements = InMemoryAchievementStore()
    private val service =
        MatchService(players, matches, EloRatingSystem(), seasons, achievements = achievements)

    private fun earnedIds(uuid: UUID) = achievements.earned(uuid).map { it.achievementId }.toSet()

    private fun settleWin(winner: UUID, loser: UUID) {
        val match = service.createMatch(queueMatch(winner, loser), MatchFormat.LOCKOUT_1V1)
        service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
            match.serverToken,
        )
    }

    private fun makeMatch(): Pair<UUID, UUID> {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        service.getOrCreatePlayer(a, "PlayerA")
        service.getOrCreatePlayer(b, "PlayerB")
        return a to b
    }

    private fun queueMatch(a: UUID, b: UUID) = QueueMatch(
        playerA = QueueEntry(a, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
        playerB = QueueEntry(b, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
    )

    private fun rating(uuid: UUID) = service.statsFor(uuid).rating

    @Test
    fun `create match generates seeds, token, and season`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)

        assertEquals(MatchStatus.PENDING, match.status)
        assertEquals(seasons.currentSeason, match.season)
        assertTrue(match.serverToken.isNotBlank())
        assertEquals(1000, match.ratingABefore)
        // two matches never share seeds/tokens
        val (c, d) = makeMatch()
        val other = service.createMatch(queueMatch(c, d), MatchFormat.LOCKOUT_1V1)
        assertNotEquals(match.serverToken, other.serverToken)
        assertNotEquals(match.settings.cardSeed, other.settings.cardSeed)
    }

    @Test
    fun `settle applies ratings and stats`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)

        val result = service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
            match.serverToken,
        )

        assertIs<MatchService.SettleResult.Settled>(result)
        assertTrue(rating(a) > 1000)
        assertTrue(rating(b) < 1000)
        assertEquals(1, service.statsFor(a).wins)
        assertEquals(1, service.statsFor(b).losses)
        val settled = matches.get(match.id)!!
        assertEquals(MatchStatus.COMPLETED, settled.status)
        assertEquals(600, settled.durationSeconds)
        assertEquals(10, settled.teamAScore)
    }

    @Test
    fun `settling a win awards first-win to the winner only`() {
        val (a, b) = makeMatch()
        settleWin(a, b)

        assertTrue("first_win" in earnedIds(a))
        assertTrue("first_win" !in earnedIds(b))
    }

    @Test
    fun `three straight wins unlock the streak milestone`() {
        val (a, b) = makeMatch()
        assertTrue("streak_3" !in earnedIds(a))

        repeat(3) { settleWin(a, b) }

        assertTrue("streak_3" in earnedIds(a))
    }

    @Test
    fun `casual matches award nothing`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.CASUAL_LOCKOUT)
        service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
            match.serverToken,
        )

        assertTrue(earnedIds(a).isEmpty())
    }

    @Test
    fun `settle with wrong token rejected`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)

        val result = service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
            "wrong-token",
        )

        assertIs<MatchService.SettleResult.BadToken>(result)
        assertEquals(1000, rating(a))
    }

    @Test
    fun `double settle rejected`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)
        val report = MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5)

        service.settle(report, match.serverToken)
        val second = service.settle(report, match.serverToken)

        assertIs<MatchService.SettleResult.AlreadySettled>(second)
        assertEquals(1, service.statsFor(a).wins)
    }

    @Test
    fun `void outcome leaves ratings untouched`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)

        val result = service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.VOID, 60, 0, 0),
            match.serverToken,
        )

        assertIs<MatchService.SettleResult.Settled>(result)
        assertEquals(1000, rating(a))
        assertEquals(0, service.statsFor(a).matchesPlayed)
        assertEquals(MatchStatus.VOIDED, matches.get(match.id)!!.status)
    }

    @Test
    fun `placement rating moves faster than established`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)
        service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
            match.serverToken,
        )
        // placement K=80, equal ratings -> +40
        assertEquals(1040, rating(a))
        assertEquals(4, service.placementMatchesRemaining(service.statsFor(a)))
    }

    @Test
    fun `season advance gives a fresh ladder but keeps history`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)
        service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
            match.serverToken,
        )
        val season1 = seasons.currentSeason
        assertEquals(1040, rating(a))
        assertEquals(1, matches.historyFor(a, season1, 10).size)

        seasons.advance()

        // fresh rating and placements in the new season
        assertEquals(1000, rating(a))
        assertEquals(5, service.placementMatchesRemaining(service.statsFor(a)))
        // previous season data still queryable
        assertEquals(1040, players.getStats(a, season1)!!.rating)
        assertEquals(1, matches.historyFor(a, season1, 10).size)
        assertEquals(0, matches.historyFor(a, seasons.currentSeason, 10).size)
    }

    @Test
    fun `settling stamps the inactivity clock and clears the decay watermark`() {
        // decay reads lastPlayedAt; without this stamp the sweep can never fire
        val (a, b) = makeMatch()
        players.upsertStats(
            service.statsFor(a).copy(decayedThrough = Instant.now().minusSeconds(60))
        )

        settleWin(a, b)

        val stats = players.getStats(a, seasons.currentSeason)!!
        assertNotNull(stats.lastPlayedAt, "a settled match did not reset the inactivity clock")
        assertNull(stats.decayedThrough, "playing must clear the decay watermark")
    }

    @Test
    fun `a casual match does not touch the inactivity clock`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.CASUAL_LOCKOUT)

        service.settle(
            MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
            match.serverToken,
        )

        assertNull(players.getStats(a, seasons.currentSeason)?.lastPlayedAt)
    }

    @Test
    fun `concurrent settles for the same player do not lose a result`() {
        val shared = UUID.randomUUID()
        service.getOrCreatePlayer(shared, "Shared")

        // one player in several simultaneous matches — each settle is a
        // read-modify-write of the same stats row
        val opponents = List(8) { index ->
            UUID.randomUUID().also { service.getOrCreatePlayer(it, "Opponent$index") }
        }
        val pending = opponents.map { opponent ->
            service.createMatch(queueMatch(shared, opponent), MatchFormat.LOCKOUT_1V1)
        }

        val threads = pending.map { match ->
            Thread {
                service.settle(
                    MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
                    match.serverToken,
                )
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        val stats = service.statsFor(shared)
        assertEquals(pending.size, stats.wins, "wins were clobbered by a concurrent settle")
        assertEquals(pending.size, stats.matchesPlayed)
        assertEquals(pending.size * 600L, stats.playtimeSeconds)
    }
}
