package dev.yabranked.client

import dev.yabranked.proto.*

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

private val log = LoggerFactory.getLogger("yabranked-state")

/**
 * A field that may only be written on the render thread.
 *
 * The rule was previously only a comment, which meant a backend callback that
 * forgot its `minecraft.execute { }` hop wrote here silently and corrupted
 * state that the renderer reads without synchronization. This makes the same
 * mistake loud: it throws in a dev environment and logs once in production,
 * where crashing the client over a torn UI field would be the worse outcome.
 */
private class RenderThread<T>(private var value: T) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        check(property.name)
        this.value = value
    }

    private fun check(name: String) {
        // null before the client exists — mod init runs on the main thread anyway
        val minecraft = Minecraft.getInstance() ?: return
        if (minecraft.isSameThread) return
        val message = "RankedState.$name written off the render thread from ${Thread.currentThread().name}"
        if (FabricLoader.getInstance().isDevelopmentEnvironment) error(message)
        if (warned.add(name)) log.warn("{} — wrap the write in minecraft.execute {{ }}", message)
    }

    private companion object {
        /** One line per field: these writes happen every tick once they happen at all. */
        val warned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    }
}

private fun <T> renderThread(initial: T): ReadWriteProperty<Any?, T> = RenderThread(initial)

/**
 * Client-side ranked session state, mutated only on the render thread
 * (network callbacks hop over via Minecraft#execute). The [renderThread]
 * delegate enforces that; the three `@Volatile` fields below are the
 * deliberate exceptions and say why.
 */
object RankedState {
    var backend: BackendClient? by renderThread(null)
    var profile: PlayerProfile? by renderThread(null)

    var queue: BackendClient.QueueSocket? by renderThread(null)
    var queueStatus: String? by renderThread(null)

    /** True while [RankedQueue] is retrying a dropped socket. There is no live
     *  socket then, but the player is still queueing and every screen should
     *  keep saying so — hence [isQueued] counts it. */
    var queueReconnecting: Boolean by renderThread(false)

    /** Format the player has selected on the ranked screen; drives the next queue join. */
    var selectedFormat: MatchFormat by renderThread(MatchFormat.LOCKOUT_1V1)

    /** Latest queue tick from the server, rendered as the searching panel. */
    var queueSnapshot: QueueServerMessage.QueueState? by renderThread(null)

    /** Set while connected to (or connecting to) a ranked match server. */
    var activeMatch: QueueServerMessage.MatchFound? by renderThread(null)

    /** Wall-clock ms when the current match server was joined, for the HUD timer. */
    var matchStartedAt: Long? by renderThread(null)

    /** The most recently completed match, kept for the report button. */
    var lastMatch: QueueServerMessage.MatchFound? by renderThread(null)
    var lastMatchReported: Boolean by renderThread(false)

    /** Rating change from the most recently completed match, for display. */
    var lastRatingChange: Int? by renderThread(null)

    /** Current consecutive-win streak, derived from recent history; 0 when the
     *  latest match was not a win. Shown on the profile and result screens. */
    var winStreak: Int by renderThread(0)

    var statusMessage: String? by renderThread(null)

    // UI flags to drive context-sensitive shortcuts without querying MC internals.
    // Volatile rather than render-thread-only: they are read from the tick and
    // the network-disconnect callback, which is exactly where the value has to
    // be visible immediately rather than one execute-hop later.
    @Volatile var onRankedScreen: Boolean = false
    @Volatile var onResultScreen: Boolean = false

    /** True while [MatchResultLoadingScreen] is the active screen, so the
     *  disconnect poll only replaces it if the player hasn't navigated away. */
    @Volatile var onResultLoading: Boolean = false

    // Visual toggles, edited on RankedOptionsScreen and persisted via Config.
    // Plain fields on purpose: Config.load() writes them from onInitializeClient,
    // before the game thread is established, so the render-thread guard cannot
    // run there — and a torn boolean here costs at most one frame of the wrong
    // colour.
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
