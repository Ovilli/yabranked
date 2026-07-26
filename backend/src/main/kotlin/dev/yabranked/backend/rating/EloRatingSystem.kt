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
    override val placementMatches: Int = 5,
    private val placementK: Double = 80.0,
    private val standardK: Double = 32.0,
    /**
     * Ratings never drop below this floor. Also the only place an otherwise
     * conserved exchange loses points: a player already at the floor cannot
     * pay the winner in full.
     */
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

        val expectedA = expectedScore(playerA.rating, playerB.rating)
        // How much A over-performed; B's is the exact negation, which is what
        // makes the exchange below symmetric.
        val surpriseA = scoreA - expectedA

        val kA = kFor(playerA)
        val kB = kFor(playerB)
        val deltaA = (kA * surpriseA).roundToInt()
        val deltaB = if (kA == kB) {
            // Same K: hand the winner exactly what the loser gives up. Rounding
            // once instead of per player matters — rounding both ends
            // independently minted or burned a point on every half-value delta.
            -deltaA
        } else {
            // The one deliberate break from conservation. A player in placements
            // has to converge on their real level fast (K=80), and charging
            // their settled opponent for that swing would make drawing a
            // placement player a coin flip on 80 points. The surplus is minted
            // (or burned) knowingly and is bounded: every account gets
            // [placementMatches] of these per season and no more.
            (kB * -surpriseA).roundToInt()
        }

        return RatingUpdate(
            playerA = RatingState((playerA.rating + deltaA).coerceAtLeast(floor), playerA.matchesPlayed + 1),
            playerB = RatingState((playerB.rating + deltaB).coerceAtLeast(floor), playerB.matchesPlayed + 1),
        )
    }
}
