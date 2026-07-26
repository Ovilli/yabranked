package dev.yabranked.backend.season

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks the active season. Ratings, placements, leaderboards, and match
 * history are all scoped to a season; advancing it opens a new ladder while the
 * closing season's rows stay exactly as they were — which is what makes
 * `GET /v1/leaderboard?season=N` that season's final standings.
 */
class SeasonService(
    initialSeason: Int = 1,
    /** Called with the new season number when it changes (persistence hook). */
    private val onChange: ((Int) -> Unit)? = null,
    /**
     * Seeds the new season's ladder from the closing one — see [SeasonRollover].
     * Null means a hard reset (everyone starts from the initial rating), which
     * is what a service constructed without a player store can offer.
     */
    private val rollover: ((from: Int, to: Int) -> Unit)? = null,
) {
    private val current = AtomicInteger(initialSeason)

    val currentSeason: Int get() = current.get()

    /** Returns the new season number. */
    fun advance(): Int {
        val to = current.incrementAndGet()
        // Seed before announcing: a client that reads the new season number
        // should never find a ladder that is still half built.
        rollover?.invoke(to - 1, to)
        onChange?.invoke(to)
        return to
    }
}
