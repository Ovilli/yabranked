package dev.yabranked.backend.match

import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
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
import kotlin.test.assertTrue

class MatchServiceTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val seasons = SeasonService()
    private val service = MatchService(players, matches, EloRatingSystem(), seasons)

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
}
