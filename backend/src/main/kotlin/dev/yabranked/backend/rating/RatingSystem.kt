package dev.yabranked.backend.rating

import dev.yabranked.proto.MatchOutcome

/**
 * A player's rating state as the rating engine sees it.
 * Kept engine-agnostic so Glicko-2/TrueSkill can be swapped in
 * for team/FFA formats later (they need extra fields like deviation,
 * which would extend this).
 */
data class RatingState(
    val rating: Int,
    val matchesPlayed: Int,
)

data class RatingUpdate(
    val playerA: RatingState,
    val playerB: RatingState,
)

/**
 * 1v1 rating engine interface. Implementations must be pure functions
 * of the inputs — persistence and ordering are the caller's problem.
 */
interface RatingSystem {
    val initialRating: Int

    /**
     * Apply one match result. [outcome] is from player A's perspective
     * via [MatchOutcome.TEAM_A_WIN]/[MatchOutcome.TEAM_B_WIN]/[MatchOutcome.DRAW].
     * [MatchOutcome.VOID] must return both states unchanged.
     */
    fun update(
        playerA: RatingState,
        playerB: RatingState,
        outcome: MatchOutcome,
    ): RatingUpdate
}
