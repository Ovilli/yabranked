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
    /**
     * Seconds until the soonest pairing this player's queue actually allows,
     * or null when nobody in range is waiting. Computed from the same band
     * expansion the tick uses, so it cannot promise a match the matchmaker
     * would refuse.
     */
    val etaSeconds: Long?,
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

    /**
     * Queue a whole party as one unit. [members] carries each player's rating
     * *in this format*, because the caller is the only thing that knows which
     * ladder applies.
     */
    suspend fun joinAsParty(partyId: UUID, members: List<Pair<UUID, Int>>, format: MatchFormat): Boolean =
        mutex.withLock { queue.enqueueParty(partyId, members, format) }

    suspend fun leave(uuid: UUID): Boolean =
        mutex.withLock { queue.remove(uuid) }

    /** Take a party's ticket out, wherever the request came from. */
    suspend fun leaveParty(partyId: UUID): Boolean =
        mutex.withLock { queue.removeTicket(partyId) }

    /** The party this player is queued with, or null when they queued alone. */
    suspend fun partyOf(uuid: UUID): UUID? = mutex.withLock { queue.partyOf(uuid) }

    suspend fun snapshot(uuid: UUID): QueueSnapshot? = mutex.withLock {
        val format = queue.formatOf(uuid) ?: return@withLock null
        QueueSnapshot(
            position = queue.positionOf(uuid),
            playersInQueue = queue.sizeOf(format),
            waitedSeconds = queue.waitedSeconds(uuid),
            etaSeconds = queue.etaSeconds(uuid),
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
        for (pairing in matched) {
            val record = try {
                // one hop for the whole call: creating a match is a transaction
                withContext(storeDispatcher) {
                    if (pairing.isSolo) {
                        matchService.createMatch(pairing.asQueueMatch(), pairing.format)
                    } else {
                        matchService.createTeamMatch(
                            MatchService.TeamMatchRequest(
                                format = pairing.format,
                                teams = pairing.sides.map { side -> side.map { it.uuid } },
                                // The public queue always plays the format as
                                // written; a party's "practise unrated" choice
                                // only applies to matches it starts itself.
                                ranked = pairing.format.ranked,
                                partyId = pairing.partyIds.filterNotNull().singleOrNull(),
                            )
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // tick() already dequeued them; put them back rather than
                // dropping a lobby's worth of players on the floor for a
                // transient store error
                log.error("could not create ${pairing.format} match for ${pairing.players.map { it.uuid }}", e)
                mutex.withLock { pairing.tickets.forEach(queue::reinstate) }
                continue
            }
            val listeners = synchronized(matchListeners) { matchListeners.toList() }
            for (listener in listeners) {
                for (entry in pairing.players) listener(entry.uuid, record)
            }
        }
    }
}
