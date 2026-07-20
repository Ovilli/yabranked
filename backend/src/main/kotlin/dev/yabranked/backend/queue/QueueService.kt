package dev.yabranked.backend.queue

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.proto.MatchFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

data class QueueSnapshot(
    val position: Int,
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
) {
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
                tickOnce()
            }
        }
    }

    suspend fun join(uuid: UUID, rating: Int, format: MatchFormat): Boolean =
        mutex.withLock { queue.enqueue(uuid, rating, format) }

    suspend fun leave(uuid: UUID): Boolean =
        mutex.withLock { queue.remove(uuid) }

    suspend fun snapshot(uuid: UUID): QueueSnapshot? = mutex.withLock {
        if (!queue.contains(uuid)) return@withLock null
        QueueSnapshot(
            position = queue.positionOf(uuid),
            playersInQueue = queue.size,
            waitedSeconds = queue.waitedSeconds(uuid),
        )
    }

    suspend fun tickOnce() {
        val matched = mutex.withLock { queue.tick() }
        for (queueMatch in matched) {
            val record = matchService.createMatch(queueMatch, queueMatch.playerA.format)
            val listeners = synchronized(matchListeners) { matchListeners.toList() }
            for (listener in listeners) {
                listener(queueMatch.playerA.uuid, record)
                listener(queueMatch.playerB.uuid, record)
            }
        }
    }
}
