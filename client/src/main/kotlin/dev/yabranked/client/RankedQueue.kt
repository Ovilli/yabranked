package dev.yabranked.client

import dev.yabranked.proto.*

import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the matchmaking socket independently of any screen.
 *
 * Queueing deliberately outlives the ranked menu — players close it and go do
 * something else while they wait — so the socket, its callbacks, and the
 * match-found handoff cannot live on a screen instance that may be gone.
 *
 * Every field here is touched on the render thread only; the blocking connect
 * and the delayed reconnects are the only work handed to
 * [YabRankedClient.workers].
 */
object RankedQueue {

    /**
     * Join and leave debounce separately. One shared window swallowed a leave
     * issued within 700 ms of a join — the player had already changed their
     * mind, and we kept them queued anyway.
     */
    private const val DEBOUNCE_MS = 700L
    private var lastJoinAt: Long = 0L
    private var lastLeaveAt: Long = 0L

    /** Reconnect backoff, and how many tries before the queue really is over. */
    private const val MAX_RECONNECTS = 5
    private const val RECONNECT_BASE_MS = 1000L
    private const val RECONNECT_MAX_MS = 8000L

    /** Format the player asked to queue for; null when they are not queueing.
     *  A dropped socket does not clear it — it is both what a reconnect
     *  re-joins with and what tells a network blip apart from a real leave. */
    private var wanted: MatchFormat? = null

    /** Consecutive reconnects since the queue last answered us; a QueueState
     *  proves the socket works, so [onMessage] clears it. */
    private var reconnects = 0
    private var pending: ScheduledFuture<*>? = null

    /** Bumped whenever the intended connection changes, so a callback from a
     *  socket the player has already abandoned cannot touch current state. */
    private var generation = 0

    /** Which queue transitions the narrator has already spoken on the current
     *  connection. QueueState lands once a second and every tick sounds the
     *  same, so only the edges are worth saying out loud. */
    private var narratedSearching = false
    private var narratedPreparing = false

    /**
     * Speak a queue transition.
     *
     * This is the half of the mod with no screen behind it: the player closed
     * the ranked menu and walked off, so the socket is the only thing that can
     * tell them anything. [net.minecraft.client.GameNarrator.saySystemNow] is a
     * no-op unless the player asked for system narration, so this never forces
     * speech on anyone who turned the narrator off.
     */
    private fun narrate(text: String) {
        Minecraft.getInstance().narrator.saySystemNow(Component.literal(text))
    }

    /** True while the current queue entry is the whole party's, not just ours. */
    private var asParty = false

    fun join(format: MatchFormat = RankedState.selectedFormat, asParty: Boolean = false) {
        if (RankedState.backend == null) {
            RankedState.queueStatus = "§cPlease sign in first"
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastJoinAt < DEBOUNCE_MS) return
        lastJoinAt = now
        if (RankedState.isQueued) return

        RankedState.lastRatingChange = null
        RankedState.queueStatus = "Joining queue…"
        wanted = format
        this.asParty = asParty
        reconnects = 0
        narratedSearching = false
        narratedPreparing = false
        connect(++generation, format)
    }

    fun leave() {
        val now = System.currentTimeMillis()
        if (now - lastLeaveAt < DEBOUNCE_MS) return
        lastLeaveAt = now

        // Retire the generation first: the close we are about to trigger must
        // not be mistaken for a drop and reconnected.
        generation++
        wanted = null
        cancelReconnect()

        RankedState.queue?.leave()
        RankedState.queue = null
        RankedState.queueSnapshot = null
        RankedState.queueStatus = null
    }

    /** Open a socket for [format] and wire its callbacks back to the render
     *  thread. Blocking, so the connect itself runs on a worker. */
    private fun connect(id: Int, format: MatchFormat) {
        val minecraft = Minecraft.getInstance()
        val backend = RankedState.backend ?: return
        YabRankedClient.workers.execute {
            val socket = backend.joinQueue(
                format = format,
                onMessage = { message -> minecraft.execute { if (id == generation) onMessage(message) } },
                onClosed = { reason -> minecraft.execute { if (id == generation) onClosed(reason) } },
                asParty = asParty,
            )
            minecraft.execute {
                if (id != generation) {
                    // The player left (or re-joined) while we were connecting;
                    // this socket is nobody's queue, so drop it.
                    socket?.leave()
                    return@execute
                }
                RankedState.queue = socket
                // A null socket already came through onClosed, which decides
                // whether there is another attempt left to make.
            }
        }
    }

    /** The socket ended. Unless the player asked for that, retry with backoff. */
    private fun onClosed(reason: String?) {
        RankedState.queue = null
        RankedState.queueSnapshot = null
        val format = wanted
        if (format == null || RankedState.activeMatch != null) {
            RankedState.queueReconnecting = false
            if (RankedState.activeMatch == null) {
                RankedState.queueStatus = reason?.let { "§7Queue closed: $it" }
            }
            return
        }

        if (reconnects >= MAX_RECONNECTS) {
            // Out of tries. Say so instead of dropping the player from the queue
            // in silence, which is what used to happen on any blip.
            wanted = null
            RankedState.queueReconnecting = false
            RankedState.queueStatus = "§cLost the queue connection — try again"
            narrate("Lost the queue connection. You are no longer queued.")
            return
        }

        // Only the first attempt speaks — the retries are the same news. Arming
        // the searching flag again means the QueueState that ends the outage
        // tells the player they are back in, which is the half that matters.
        if (reconnects == 0) narrate("Queue connection lost. Reconnecting.")
        narratedSearching = false
        narratedPreparing = false

        val delay = (RECONNECT_BASE_MS shl reconnects).coerceAtMost(RECONNECT_MAX_MS)
        reconnects++
        RankedState.queueReconnecting = true
        RankedState.queueStatus = "§7Reconnecting… ($reconnects/$MAX_RECONNECTS)"

        val id = ++generation
        val minecraft = Minecraft.getInstance()
        pending = YabRankedClient.workers.schedule(
            { minecraft.execute { if (id == generation && wanted != null) connect(id, format) } },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelReconnect() {
        pending?.cancel(false)
        pending = null
        reconnects = 0
        RankedState.queueReconnecting = false
    }

    private fun onMessage(message: QueueServerMessage) {
        val minecraft = Minecraft.getInstance()
        // Anything from the server means this socket is healthy, so the next
        // drop gets a full run of retries rather than the tail of the last one.
        reconnects = 0
        RankedState.queueReconnecting = false
        when (message) {
            is QueueServerMessage.QueueState -> {
                RankedState.queueSnapshot = message
                RankedState.queueStatus = null
                // Position and headcount churn every tick and none of it is
                // worth a second of speech; the two state changes underneath
                // them are, and each is spoken once.
                if (message.preparingMatch) {
                    if (!narratedPreparing) {
                        narratedPreparing = true
                        narrate("Opponent found. Preparing the match server.")
                    }
                } else if (!narratedSearching) {
                    narratedSearching = true
                    narrate("In queue. Searching for an opponent.")
                }
            }

            is QueueServerMessage.QueueError -> {
                // A refusal, not a dropped connection: the server has told us
                // why it will not queue this player, and the socket is about to
                // close. Retiring the attempt is what stops the reconnect loop —
                // otherwise onClosed sees `wanted` still set and tries again a
                // second later, forever, because any message (this one included)
                // resets the backoff.
                generation++
                wanted = null
                cancelReconnect()
                RankedState.queue = null
                RankedState.queueSnapshot = null
                RankedState.queueStatus = "§c${message.message}"
                narrate("Queue error. ${message.message}")
            }

            is QueueServerMessage.QueueCancelled -> {
                // The party changed under the search — the leader left, or the
                // roster no longer fills the format. The server has already
                // dropped the entry, so this is a real end to the queue and not
                // a blip to reconnect through.
                generation++
                wanted = null
                cancelReconnect()
                RankedState.queue = null
                RankedState.queueSnapshot = null
                RankedState.queueStatus = "§e${message.reason}"
                narrate("Search cancelled. ${message.reason}")
            }

            is QueueServerMessage.MatchFound -> {
                // The queue's job is done: retire it so the close that follows
                // is not treated as a drop worth reconnecting.
                generation++
                wanted = null
                cancelReconnect()

                RankedState.activeMatch = message
                RankedState.queue = null
                RankedState.queueSnapshot = null
                RankedState.queueStatus = null

                // Said before the screen opens rather than left to the screen's
                // own (delayed) narration: the countdown is five seconds and the
                // player may well be looking at something else entirely.
                narrate("Match found against ${message.opponent.name}. Connecting shortly.")

                // takes over from whatever screen the player is on, including
                // none; the countdown owns the transition so there is no parent
                // to return to
                minecraft.setScreenAndShow(MatchFoundScreen(null, message))
            }
        }
    }
}
