package dev.yabranked.backend.season

import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks the active season. Ratings, placements, leaderboards, and match
 * history are all scoped to a season; advancing it gives everyone a fresh
 * ladder (records from previous seasons remain queryable).
 */
class SeasonService(
    initialSeason: Int = 1,
    /** Called with the new season number when it changes (persistence hook). */
    private val onChange: ((Int) -> Unit)? = null,
) {
    private val current = AtomicInteger(initialSeason)

    val currentSeason: Int get() = current.get()

    /** Returns the new season number. */
    fun advance(): Int = current.incrementAndGet().also { onChange?.invoke(it) }
}
