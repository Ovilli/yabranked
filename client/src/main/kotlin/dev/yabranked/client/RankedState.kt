package dev.yabranked.client

/**
 * Client-side ranked session state, mutated only on the render thread
 * (network callbacks hop over via Minecraft#execute).
 */
object RankedState {
    var backend: BackendClient? = null
    var profile: WireProfile? = null

    var queue: BackendClient.QueueSocket? = null
    var queueStatus: String? = null

    /** Set while connected to (or connecting to) a ranked match server. */
    var activeMatch: WireQueueServerMessage.MatchFound? = null

    /** The most recently completed match, kept for the report button. */
    var lastMatch: WireQueueServerMessage.MatchFound? = null
    var lastMatchReported: Boolean = false

    /** Rating change from the most recently completed match, for display. */
    var lastRatingChange: Int? = null

    var statusMessage: String? = null

    val isAuthenticated: Boolean get() = backend?.session != null
    val isQueued: Boolean get() = queue != null

    fun reset() {
        queue?.leave()
        queue = null
        queueStatus = null
        activeMatch = null
        statusMessage = null
    }
}
