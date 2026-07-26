package dev.yabranked.client

import dev.yabranked.proto.*

/**
 * Client-side ranked session state, mutated only on the render thread
 * (network callbacks hop over via Minecraft#execute).
 */
object RankedState {
    var backend: BackendClient? = null
    var profile: PlayerProfile? = null

    var queue: BackendClient.QueueSocket? = null
    var queueStatus: String? = null

    /** True while [RankedQueue] is retrying a dropped socket. There is no live
     *  socket then, but the player is still queueing and every screen should
     *  keep saying so — hence [isQueued] counts it. */
    var queueReconnecting: Boolean = false

    /** Format the player has selected on the ranked screen; drives the next queue join. */
    var selectedFormat: MatchFormat = MatchFormat.LOCKOUT_1V1

    /** Latest queue tick from the server, rendered as the searching panel. */
    var queueSnapshot: QueueServerMessage.QueueState? = null

    /** Set while connected to (or connecting to) a ranked match server. */
    var activeMatch: QueueServerMessage.MatchFound? = null

    /** Wall-clock ms when the current match server was joined, for the HUD timer. */
    var matchStartedAt: Long? = null

    /** The most recently completed match, kept for the report button. */
    var lastMatch: QueueServerMessage.MatchFound? = null
    var lastMatchReported: Boolean = false

    /** Rating change from the most recently completed match, for display. */
    var lastRatingChange: Int? = null

    /** Current consecutive-win streak, derived from recent history; 0 when the
     *  latest match was not a win. Shown on the profile and result screens. */
    var winStreak: Int = 0

    var statusMessage: String? = null

    // UI flags to drive context-sensitive shortcuts without querying MC internals
    @Volatile var onRankedScreen: Boolean = false
    @Volatile var onResultScreen: Boolean = false

    /** True while [MatchResultLoadingScreen] is the active screen, so the
     *  disconnect poll only replaces it if the player hasn't navigated away. */
    @Volatile var onResultLoading: Boolean = false

    // Visual toggles, edited on RankedOptionsScreen and persisted via Config.
    var showFlags: Boolean = true
    var hideOwnFlag: Boolean = false
    /** Hide your own MMR on the profile / result screens. */
    var hideElo: Boolean = false
    /** Hide the opponent's MMR on the match-found screen and in-match HUD. */
    var hideOpponentElo: Boolean = false
    /** Colour-blind-safe win/loss palette (blue/orange instead of green/red). */
    var colorblind: Boolean = false

    /** Consecutive wins from the front of a newest-first history list. */
    fun currentWinStreak(entries: List<MatchHistoryEntry>): Int {
        var n = 0
        for (e in entries) {
            if (e.result == "win") n++ else break
        }
        return n
    }

    val isAuthenticated: Boolean get() = backend?.session != null
    val isQueued: Boolean get() = queue != null || queueReconnecting

    fun reset() {
        queue?.leave()
        queue = null
        queueReconnecting = false
        queueStatus = null
        queueSnapshot = null
        activeMatch = null
        matchStartedAt = null
        lastMatch = null
        lastMatchReported = false
        lastRatingChange = null
        statusMessage = null
        // Do not clear backend/profile here automatically, as reset may be
        // used for transient UI cleanup while the session stays valid.
    }
}
