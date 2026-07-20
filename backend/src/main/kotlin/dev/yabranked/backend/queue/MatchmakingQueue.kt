package dev.yabranked.backend.queue

import dev.yabranked.proto.MatchFormat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

data class QueueEntry(
    val uuid: UUID,
    val rating: Int,
    val format: MatchFormat,
    val enqueuedAt: Instant,
)

data class QueueMatch(
    val playerA: QueueEntry,
    val playerB: QueueEntry,
)

/**
 * Matchmaking with MMR-band expansion: each player accepts opponents
 * within [initialBand] rating points, widening by [bandPerSecond] the
 * longer they wait (capped at [maxBand]). A pair matches when both
 * players' current bands cover the rating gap — the MCSR-style
 * fairness/wait-time trade-off is tuned entirely via these knobs.
 *
 * Not thread-safe; callers synchronize (the service ticks it from one coroutine).
 */
class MatchmakingQueue(
    private val initialBand: Int = 100,
    private val bandPerSecond: Double = 5.0,
    private val maxBand: Int = 1000,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val entries = LinkedHashMap<UUID, QueueEntry>()

    val size: Int get() = entries.size

    fun contains(uuid: UUID): Boolean = uuid in entries

    fun positionOf(uuid: UUID): Int = entries.keys.indexOf(uuid) + 1

    fun waitedSeconds(uuid: UUID): Long =
        entries[uuid]?.let { Duration.between(it.enqueuedAt, clock.instant()).seconds } ?: 0

    /** Returns false if the player is already queued. */
    fun enqueue(uuid: UUID, rating: Int, format: MatchFormat): Boolean {
        if (uuid in entries) return false
        entries[uuid] = QueueEntry(uuid, rating, format, clock.instant())
        return true
    }

    fun remove(uuid: UUID): Boolean = entries.remove(uuid) != null

    private fun bandOf(entry: QueueEntry, now: Instant): Int {
        val waited = Duration.between(entry.enqueuedAt, now).seconds
        return (initialBand + (waited * bandPerSecond)).toInt().coerceAtMost(maxBand)
    }

    private fun accepts(a: QueueEntry, b: QueueEntry, now: Instant): Boolean {
        if (a.format != b.format) return false
        val gap = abs(a.rating - b.rating)
        return gap <= bandOf(a, now) && gap <= bandOf(b, now)
    }

    /**
     * Find all matches currently possible and remove the matched players.
     * Longest-waiting players get priority; among candidates, the closest
     * rating wins.
     */
    fun tick(): List<QueueMatch> {
        val now = clock.instant()
        val matches = mutableListOf<QueueMatch>()

        // iteration order == enqueue order == wait-time priority
        val waiting = entries.values.toMutableList()
        while (waiting.size >= 2) {
            val seeker = waiting.removeFirst()
            val opponent = waiting
                .filter { accepts(seeker, it, now) }
                .minByOrNull { abs(it.rating - seeker.rating) }
                ?: continue

            waiting.remove(opponent)
            entries.remove(seeker.uuid)
            entries.remove(opponent.uuid)
            matches += QueueMatch(seeker, opponent)
        }

        return matches
    }
}
