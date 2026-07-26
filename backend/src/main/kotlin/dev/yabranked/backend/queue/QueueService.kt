package dev.yabranked.backend.queue

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.ops.MatchmakingMetrics
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.StoreDispatchers
import dev.yabranked.proto.MatchFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class QueueSnapshot(
    /** 1-based place among the players waiting for the same format. */
    val position: Int,
    /**
     * Players waiting for *this player's* format. A global count told someone
     * queueing for LOCKOUT_1V1 that a crowd of casual players was about to
     * match them.
     */
    val playersInQueue: Int,
    val waitedSeconds: Long,
)

/**
 * Owns the [MatchmakingQueue], ticks it periodically, and turns queue
 * matches into match records. All queue access goes through [mutex]
 * since the underlying queue is not thread-safe.
 */
class QueueService(
    private val queue: MatchmakingQueue,
    private val matchService: MatchService,
    private val tickInterval: kotlin.time.Duration = 1.seconds,
    /**
     * The tick loop is launched into the server's scope, so creating a match
     * would otherwise run its transaction on the event loop.
     */
    private val storeDispatcher: CoroutineDispatcher = StoreDispatchers.default,
    /**
     * Queue depth and tick health. Pushed from the tick rather than polled: a
     * gauge callback would have to take [mutex], and a metrics scrape must
     * never be able to block matchmaking.
     */
    private val metrics: MatchmakingMetrics = MatchmakingMetrics.NONE,
) {
    private val log = LoggerFactory.getLogger("queue")
    private val mutex = Mutex()
    private var tickJob: Job? = null

    /** Called with (playerUuid, matchRecord) for each matched player. */
    private val matchListeners = mutableListOf<(UUID, MatchRecord) -> Unit>()

    fun onPlayerMatched(listener: (UUID, MatchRecord) -> Unit) {
        synchronized(matchListeners) { matchListeners += listener }
    }

    fun removeListener(listener: (UUID, MatchRecord) -> Unit) {
        synchronized(matchListeners) { matchListeners -= listener }
    }

    fun start(scope: CoroutineScope) {
        check(tickJob == null) { "already started" }
        tickJob = scope.launch {
            while (true) {
                delay(tickInterval)
                // A throw here used to kill the job for the lifetime of the
                // process: matchmaking would stop dead while every endpoint
                // kept answering 200. Never let one bad tick end the loop.
                val startedAt = System.nanoTime()
                try {
                    tickOnce()
                    metrics.tickCompleted(System.nanoTime() - startedAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    metrics.tickFailed()
                    log.error("matchmaking tick failed; continuing", e)
                }
            }
        }
    }

    /** True while the matchmaking loop is alive; used by the readiness probe. */
    val isRunning: Boolean get() = tickJob?.isActive == true

    /** Stops the tick loop. */
    suspend fun stop() {
        tickJob?.cancelAndJoin()
        tickJob = null
    }

    suspend fun join(uuid: UUID, rating: Int, format: MatchFormat): Boolean =
        mutex.withLock { queue.enqueue(uuid, rating, format) }

    suspend fun leave(uuid: UUID): Boolean =
        mutex.withLock { queue.remove(uuid) }

    suspend fun snapshot(uuid: UUID): QueueSnapshot? = mutex.withLock {
        val format = queue.formatOf(uuid) ?: return@withLock null
        QueueSnapshot(
            position = queue.positionOf(uuid),
            playersInQueue = queue.sizeOf(format),
            waitedSeconds = queue.waitedSeconds(uuid),
        )
    }

    suspend fun tickOnce() {
        val matched = mutex.withLock {
            queue.tick().also {
                // read under the same lock as the tick, so the depth reported is
                // the one the pairing actually saw
                for (format in MatchFormat.entries) metrics.queueDepth(format, queue.sizeOf(format))
            }
        }
        for (queueMatch in matched) {
            val record = try {
                // one hop for the whole call: createMatch is a transaction
                withContext(storeDispatcher) {
                    matchService.createMatch(queueMatch, queueMatch.playerA.format)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // tick() already dequeued them; put them back rather than
                // dropping two players on the floor for a transient store error
                log.error("could not create match for ${queueMatch.playerA.uuid} vs ${queueMatch.playerB.uuid}", e)
                mutex.withLock {
                    for (entry in listOf(queueMatch.playerA, queueMatch.playerB)) {
                        queue.enqueue(entry.uuid, entry.rating, entry.format)
                    }
                }
                continue
            }
            val listeners = synchronized(matchListeners) { matchListeners.toList() }
            for (listener in listeners) {
                listener(queueMatch.playerA.uuid, record)
                listener(queueMatch.playerB.uuid, record)
            }
        }
    }
}
