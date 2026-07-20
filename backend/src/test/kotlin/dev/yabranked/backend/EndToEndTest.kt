package dev.yabranked.backend

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full backend flow without HTTP: register two players, queue both,
 * tick matchmaking, settle the produced match, verify ladder movement.
 * (The HTTP layer on top of this is covered by ApiTest.)
 */
class EndToEndTest {

    @Test
    fun `two players queue, match, and settle`() = runTest {
        val players = InMemoryPlayerStore()
        val matches = InMemoryMatchStore()
        val seasons = SeasonService()
        val matchService = MatchService(players, matches, EloRatingSystem(), seasons)
        val queueService = QueueService(MatchmakingQueue(), matchService)

        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()
        matchService.getOrCreatePlayer(alice, "Alice")
        matchService.getOrCreatePlayer(bob, "Bob")

        var created: MatchRecord? = null
        matchService.onMatchCreated { created = it }

        assertTrue(queueService.join(alice, 1000, MatchFormat.LOCKOUT_1V1))
        assertTrue(queueService.join(bob, 1000, MatchFormat.LOCKOUT_1V1))
        queueService.tickOnce()

        val match = assertNotNull(created, "match should be created after tick")
        assertEquals(setOf(alice, bob), setOf(match.playerA, match.playerB))
        assertEquals(MatchStatus.PENDING, match.status)

        val report = MatchResultReport(
            matchId = match.id.toString(),
            outcome = MatchOutcome.TEAM_A_WIN,
            durationSeconds = 950,
            teamAScore = 12,
            teamBScore = 7,
        )
        val settled = matchService.settle(report, match.serverToken)
        assertTrue(settled is MatchService.SettleResult.Settled)

        val season = seasons.currentSeason
        val winner = players.getStats(match.playerA, season)!!
        val loser = players.getStats(match.playerB, season)!!
        assertTrue(winner.rating > 1000)
        assertTrue(loser.rating < 1000)
        assertEquals(listOf(winner), players.topByRating(season, limit = 1, minMatches = 1))

        // match history visible for both players
        assertEquals(1, matches.historyFor(alice, season, 10).size)
        assertEquals(1, matches.historyFor(bob, season, 10).size)
    }
}
