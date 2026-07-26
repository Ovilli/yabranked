package dev.yabranked.backend.rating

import dev.yabranked.proto.MatchOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EloRatingSystemTest {

    private val elo = EloRatingSystem(
        initialRating = 1000,
        placementMatches = 5,
        placementK = 80.0,
        standardK = 32.0,
    )

    private fun placed(rating: Int) = RatingState(rating, matchesPlayed = 10)

    @Test
    fun `equal ratings, winner takes half of K`() {
        val update = elo.update(placed(1000), placed(1000), MatchOutcome.TEAM_A_WIN)
        assertEquals(1016, update.playerA.rating)
        assertEquals(984, update.playerB.rating)
    }

    @Test
    fun `draw between equal ratings changes nothing`() {
        val update = elo.update(placed(1000), placed(1000), MatchOutcome.DRAW)
        assertEquals(1000, update.playerA.rating)
        assertEquals(1000, update.playerB.rating)
    }

    @Test
    fun `underdog win moves more than favorite win`() {
        val underdogWin = elo.update(placed(900), placed(1100), MatchOutcome.TEAM_A_WIN)
        val favoriteWin = elo.update(placed(1100), placed(900), MatchOutcome.TEAM_A_WIN)
        val underdogGain = underdogWin.playerA.rating - 900
        val favoriteGain = favoriteWin.playerA.rating - 1100
        assertTrue(underdogGain > favoriteGain, "underdog gained $underdogGain, favorite gained $favoriteGain")
    }

    @Test
    fun `placement matches use the higher K factor`() {
        val placementPlayer = RatingState(1000, matchesPlayed = 0)
        val update = elo.update(placementPlayer, placed(1000), MatchOutcome.TEAM_A_WIN)
        assertEquals(1040, update.playerA.rating) // 80 * 0.5
        assertEquals(984, update.playerB.rating) // 32 * 0.5
    }

    @Test
    fun `void outcome changes nothing including matches played`() {
        val a = placed(1000)
        val b = placed(1200)
        val update = elo.update(a, b, MatchOutcome.VOID)
        assertEquals(a, update.playerA)
        assertEquals(b, update.playerB)
    }

    @Test
    fun `rating never drops below floor`() {
        val update = elo.update(RatingState(5, 10), placed(1000), MatchOutcome.TEAM_B_WIN)
        assertTrue(update.playerA.rating >= 0)
    }

    @Test
    fun `matches played increments on rated outcomes`() {
        val update = elo.update(placed(1000), placed(1000), MatchOutcome.TEAM_A_WIN)
        assertEquals(11, update.playerA.matchesPlayed)
        assertEquals(11, update.playerB.matchesPlayed)
    }

    /** Net rating the ladder gained (or lost) across one settle. */
    private fun exchange(a: RatingState, b: RatingState, outcome: MatchOutcome): Int {
        val update = elo.update(a, b, outcome)
        return (update.playerA.rating - a.rating) + (update.playerB.rating - b.rating)
    }

    @Test
    fun `between two placed players the exchange is conserved`() {
        // rounding each delta independently minted or burned a point on every
        // half-value swing, so the ladder's total drifted with every match
        for (outcome in listOf(MatchOutcome.TEAM_A_WIN, MatchOutcome.TEAM_B_WIN, MatchOutcome.DRAW)) {
            for (gap in listOf(0, 37, 111, 250, 419)) {
                assertEquals(
                    0,
                    exchange(placed(1500), placed(1500 - gap), outcome),
                    "$outcome at a $gap gap moved the ladder's total rating",
                )
            }
        }
    }

    @Test
    fun `the winner gains exactly what the loser gives up`() {
        val a = placed(1600)
        val b = placed(1450)

        val update = elo.update(a, b, MatchOutcome.TEAM_A_WIN)

        assertEquals(update.playerA.rating - a.rating, -(update.playerB.rating - b.rating))
    }

    @Test
    fun `a mixed placement match breaks conservation deliberately and boundedly`() {
        // charging a settled opponent for a placement player's fast convergence
        // would make drawing one a coin flip on 80 points, so the surplus is
        // minted knowingly — and only for a player's first few matches a season
        val placementPlayer = RatingState(1000, matchesPlayed = 0)
        val established = placed(1000)

        val net = exchange(placementPlayer, established, MatchOutcome.TEAM_A_WIN)

        assertEquals(24, net, "placement gains 40 while the opponent pays 16")
    }
}
