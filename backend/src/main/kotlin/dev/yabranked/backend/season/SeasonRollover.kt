package dev.yabranked.backend.season

import dev.yabranked.backend.rating.RatingSystem
import dev.yabranked.backend.store.PlayerStore
import org.slf4j.LoggerFactory

/**
 * Carries a closing season's ladder into the new one.
 *
 * A hard reset threw away everything the ladder had learned: every Netherite
 * player spent their first evening of the new season farming Coal opponents.
 * Instead each placed player is seeded at [carry] of their distance from the
 * initial rating — half the signal kept, half the season's story left to write —
 * and still owes their placement matches, so the seed is a starting guess the
 * new season can argue with rather than a verdict.
 *
 * Nothing is deleted: the closing season's rows are left exactly as they were
 * and stay readable as that season's final standings
 * (`GET /v1/leaderboard?season=N`), because after the rollover nothing writes
 * to them again.
 */
class SeasonRollover(
    private val players: PlayerStore,
    private val rating: RatingSystem,
    /** Share of a player's distance from the initial rating that survives the reset. */
    private val carry: Double = DEFAULT_CARRY,
    /** Ceiling on rows carried over; the tail of the ladder is all near the initial rating anyway. */
    private val limit: Int = DEFAULT_LIMIT,
) {
    private val log = LoggerFactory.getLogger("season")

    fun roll(from: Int, to: Int) {
        // Only placed ratings are carried: a rating with unfinished placements
        // is not yet a claim about anything, so it is not signal worth keeping.
        val standings = players.topByRating(from, limit = limit, minMatches = rating.placementMatches)
        for (stats in standings) {
            val seed = seedRating(stats.rating)
            players.upsertStats(
                stats.copy(
                    season = to,
                    rating = seed,
                    matchesPlayed = 0,
                    wins = 0,
                    losses = 0,
                    draws = 0,
                    playtimeSeconds = 0,
                    forfeits = 0,
                    peakRating = seed,
                    // a seeded row has never been played, so it cannot be idle yet
                    lastPlayedAt = null,
                    decayedThrough = null,
                )
            )
        }
        log.info("season {} -> {}: carried {} placed rating(s)", from, to, standings.size)
    }

    /** Soft reset: pull [rating] toward the initial rating without flattening it. */
    fun seedRating(rating: Int): Int {
        val initial = this.rating.initialRating
        return initial + Math.round((rating - initial) * carry).toInt()
    }

    companion object {
        const val DEFAULT_CARRY = 0.5
        const val DEFAULT_LIMIT = 100_000
    }
}
