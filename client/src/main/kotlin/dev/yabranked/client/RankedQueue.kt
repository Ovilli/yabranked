package dev.yabranked.client

import net.minecraft.client.Minecraft

/**
 * Owns the matchmaking socket independently of any screen.
 *
 * Queueing deliberately outlives the ranked menu — players close it and go do
 * something else while they wait — so the socket, its callbacks, and the
 * match-found handoff cannot live on a screen instance that may be gone.
 */
object RankedQueue {

    private const val DEBOUNCE_MS = 700L
    @Volatile private var lastActionAt: Long = 0L

    fun join(format: String = RankedState.selectedFormat.id) {
        val minecraft = Minecraft.getInstance()
        val backend = RankedState.backend ?: run {
            RankedState.queueStatus = "§cPlease sign in first"
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastActionAt < DEBOUNCE_MS) return
        lastActionAt = now
        if (RankedState.isQueued) return

        RankedState.lastRatingChange = null
        RankedState.queueStatus = "Joining queue…"

        YabRankedClient.workers.execute {
            val socket = backend.joinQueue(
                format = format,
                onMessage = { message -> minecraft.execute { onMessage(message) } },
                onClosed = { reason ->
                    minecraft.execute {
                        RankedState.queue = null
                        RankedState.queueSnapshot = null
                        if (RankedState.activeMatch == null) {
                            RankedState.queueStatus = reason?.let { "§7Queue closed: $it" }
                        }
                    }
                },
            )
            minecraft.execute {
                RankedState.queue = socket
                if (socket == null) RankedState.queueStatus = "§cCould not join the queue"
            }
        }
    }

    fun leave() {
        val now = System.currentTimeMillis()
        if (now - lastActionAt < DEBOUNCE_MS) return
        lastActionAt = now

        RankedState.queue?.leave()
        RankedState.queue = null
        RankedState.queueSnapshot = null
        RankedState.queueStatus = null
    }

    private fun onMessage(message: WireQueueServerMessage) {
        val minecraft = Minecraft.getInstance()
        when (message) {
            is WireQueueServerMessage.QueueState -> {
                RankedState.queueSnapshot = message
                RankedState.queueStatus = null
            }

            is WireQueueServerMessage.QueueError -> {
                RankedState.queueSnapshot = null
                RankedState.queueStatus = "§c${message.message}"
            }

            is WireQueueServerMessage.MatchFound -> {
                RankedState.activeMatch = message
                RankedState.queue = null
                RankedState.queueSnapshot = null
                RankedState.queueStatus = null

                // takes over from whatever screen the player is on, including
                // none; the countdown owns the transition so there is no parent
                // to return to
                minecraft.setScreenAndShow(MatchFoundScreen(null, message))
            }
        }
    }
}
