package dev.yabranked.backend.store

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Deletes replays nobody asked to keep.
 *
 * Every match records one, and a recording is orders of magnitude larger than
 * the match row it belongs to, so the default has to be that they go away. The
 * store's own rules decide what survives — a player's save, a moderator hold —
 * and this only asks it, on an interval, to act on what is past its date.
 *
 * Shaped like [dev.yabranked.backend.rating.DecaySweep] on purpose, including
 * the "a failed pass must not end the loop" guard: a sweep that dies silently
 * means unbounded growth that nothing reports until the disk is full.
 */
class ReplaySweep(
    private val replays: ReplayStore,
    /**
     * Where the packets are. Dropping the row without them would leave the bytes
     * on disk forever, which is the one outcome a retention sweep exists to
     * prevent — and the bytes are now essentially all of the recording's size.
     */
    private val blobs: ReplayBlobStore,
    private val clock: Clock = Clock.systemUTC(),
    private val interval: kotlin.time.Duration = 1.hours,
) {
    private val log = LoggerFactory.getLogger("replays")
    private var job: Job? = null

    /** One pass. Returns how many recordings were dropped. */
    fun sweep(): Int {
        val dropped = replays.pruneExpired(clock.instant())
        // Rows first, bytes second. The other order would briefly leave a row
        // pointing at a recording that had already been deleted, which is a
        // player opening a replay and being handed an empty world.
        for (matchId in dropped) {
            runCatching { blobs.delete(matchId) }
                .onFailure { log.warn("could not delete replay data for {}", matchId, it) }
        }
        if (dropped.isNotEmpty()) log.info("dropped {} expired replay(s)", dropped.size)
        return dropped.size
    }

    fun start(scope: CoroutineScope) {
        check(job == null) { "already started" }
        job = scope.launch {
            while (true) {
                try {
                    sweep()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.error("replay sweep failed; continuing", e)
                }
                delay(interval)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }
}
