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
}
