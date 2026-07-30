package dev.yabranked.backend.rating

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Rating for matches with more than one player a side.
 *
 * Separate from [RatingSystem] rather than a generalisation of it: the 1v1
 * ladder's exact conservation and its placement-surplus rule are properties
 * worth keeping unchanged, and a team result is a different question anyway
 * ("did this *side* over-perform", then distribute).
 *
 * Each mode keeps its own ladder — a 3v3 rating is not a 1v1 rating — so this
 * is always called with the ratings of one specific format.
 */
interface TeamRatingSystem {
    val initialRating: Int
    val placementMatches: Int

    /**
     * Apply one result. [sides] is side-ordered; [winner] is the winning side's
     * index, or null for a draw. Every returned state has its match count
     * incremented, including on a draw.
     */
    fun update(sides: List<List<RatingState>>, winner: Int?): List<List<RatingState>>
}

/**
 * Team Elo: a side's strength is its mean rating, the side's surprise is
 * shared by every member, and each member's own K-factor decides how far it
 * moves them.
 *
 * Using the mean rather than each player's own rating is the point — it is
 * what makes carrying a lower-rated teammate worth more than beating an equal
 * opponent alone, and what stops a high-rated player farming rating by queueing
 * with beginners.
 */
class EloTeamRatingSystem(
    override val initialRating: Int = 1000,
    override val placementMatches: Int = 5,
    private val placementK: Double = 64.0,
    private val standardK: Double = 28.0,
    private val floor: Int = 0,
) : TeamRatingSystem {

    private fun kFor(state: RatingState): Double =
        if (state.matchesPlayed < placementMatches) placementK else standardK

    private fun expectedScore(own: Int, opponent: Int): Double =
        1.0 / (1.0 + 10.0.pow((opponent - own) / 400.0))

    private fun mean(side: List<RatingState>): Int =
        if (side.isEmpty()) initialRating else side.sumOf { it.rating } / side.size

    override fun update(sides: List<List<RatingState>>, winner: Int?): List<List<RatingState>> {
        require(sides.size >= 2) { "a match needs at least two sides" }
        require(winner == null || winner in sides.indices) { "winning side $winner is not in this match" }

        val means = sides.map(::mean)
        val opponents = sides.size - 1

        return sides.mapIndexed { sideIndex, side ->
            // Sum the side's surprise across every opposing side, then spread
            // one opponent's worth of K over it: a four-way free-for-all must
            // not move ratings three times as far as a 1v1 did.
            var surprise = 0.0
            for (other in sides.indices) {
                if (other == sideIndex) continue
                val actual = when (winner) {
                    null -> 0.5
                    sideIndex -> 1.0
                    other -> 0.0
                    // neither of us won: a third side did, so we drew with each
                    // other and both lost to them — already counted above
                    else -> 0.5
                }
                surprise += actual - expectedScore(means[sideIndex], means[other])
            }
            val sideSurprise = surprise / opponents

            side.map { player ->
                val delta = (kFor(player) * sideSurprise).roundToInt()
                RatingState(
                    rating = (player.rating + delta).coerceAtLeast(floor),
                    matchesPlayed = player.matchesPlayed + 1,
                )
            }
        }
    }
}
