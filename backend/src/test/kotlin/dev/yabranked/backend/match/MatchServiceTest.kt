package dev.yabranked.backend.match

import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.EloRatingSystem
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
    private val service = MatchService(players, matches, EloRatingSystem())

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

    @Test
    fun `create match generates seeds and token`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)

        assertEquals(MatchStatus.PENDING, match.status)
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
        val playerA = players.get(a)!!
        val playerB = players.get(b)!!
        assertTrue(playerA.rating > 1000)
        assertTrue(playerB.rating < 1000)
        assertEquals(1, playerA.wins)
        assertEquals(1, playerB.losses)
        assertEquals(MatchStatus.COMPLETED, matches.get(match.id)!!.status)
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
        assertEquals(1000, players.get(a)!!.rating)
    }

    @Test
    fun `double settle rejected`() {
        val (a, b) = makeMatch()
        val match = service.createMatch(queueMatch(a, b), MatchFormat.LOCKOUT_1V1)
        val report = MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5)

        service.settle(report, match.serverToken)
        val second = service.settle(report, match.serverToken)

        assertIs<MatchService.SettleResult.AlreadySettled>(second)
        assertEquals(1, players.get(a)!!.wins)
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
        assertEquals(1000, players.get(a)!!.rating)
        assertEquals(0, players.get(a)!!.matchesPlayed)
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
        assertEquals(1040, players.get(a)!!.rating)
        assertEquals(4, service.placementMatchesRemaining(players.get(a)!!))
    }
}
