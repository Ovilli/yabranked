package dev.yabranked.backend.rating

import dev.yabranked.proto.MatchOutcome
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Standard Elo with a K-factor schedule:
 * placement matches move fast, established ratings move slower.
 */
class EloRatingSystem(
    override val initialRating: Int = 1000,
    private val placementMatches: Int = 5,
    private val placementK: Double = 80.0,
    private val standardK: Double = 32.0,
    /** Ratings never drop below this floor. */
    private val floor: Int = 0,
) : RatingSystem {

    private fun kFor(state: RatingState): Double =
        if (state.matchesPlayed < placementMatches) placementK else standardK

    private fun expectedScore(own: Int, opponent: Int): Double =
        1.0 / (1.0 + 10.0.pow((opponent - own) / 400.0))

    override fun update(
        playerA: RatingState,
        playerB: RatingState,
        outcome: MatchOutcome,
    ): RatingUpdate {
        if (outcome == MatchOutcome.VOID) {
            return RatingUpdate(playerA, playerB)
        }

        val scoreA = when (outcome) {
            MatchOutcome.TEAM_A_WIN -> 1.0
            MatchOutcome.TEAM_B_WIN -> 0.0
            MatchOutcome.DRAW -> 0.5
            MatchOutcome.VOID -> error("unreachable")
        }
        val scoreB = 1.0 - scoreA

        val expectedA = expectedScore(playerA.rating, playerB.rating)
        val expectedB = 1.0 - expectedA

        val newA = (playerA.rating + kFor(playerA) * (scoreA - expectedA)).roundToInt()
        val newB = (playerB.rating + kFor(playerB) * (scoreB - expectedB)).roundToInt()

        return RatingUpdate(
            playerA = RatingState(newA.coerceAtLeast(floor), playerA.matchesPlayed + 1),
            playerB = RatingState(newB.coerceAtLeast(floor), playerB.matchesPlayed + 1),
        )
    }
}
