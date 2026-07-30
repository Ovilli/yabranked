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

    // --- Social ---
    // The party socket is the client's only source of party truth: the server
    // pushes the whole [PartyView] on every change, so nothing here is ever
    // edited locally in anticipation of a reply.

    var party: PartyView? by renderThread(null)

    /** An invite waiting for an answer; the newest one wins. */
    var partyInvite: PartyInviteView? by renderThread(null)

    /** Incoming friend requests, refreshed from the friends screen and pushed
     *  live by the party socket while the player is in the menus. */
    var friendRequests: Int by renderThread(0)

    /**
     * Bumped whenever the party socket brings news that makes a social list
     * stale — a friend request arriving, a party changing shape.
     *
     * The friends screen fetches over HTTP, so a push cannot hand it the new
     * row; it can only tell it that what it is showing is out of date. Screens
     * compare this against the value they last loaded at and refetch when it
     * moves, which is what makes an incoming request appear without the player
     * having to leave the screen and come back.
     */
    var socialRevision: Int by renderThread(0)

    /** True when the player leads the party — the only one who may change it. */
    val isPartyLeader: Boolean
        get() {
            val view = party ?: return false
            val self = profile?.uuid ?: return false
            return view.leader == self
        }

    /** Whether the queue join should be a party join. */
    val queueAsParty: Boolean get() = party != null && isPartyLeader

    // UI flags to drive context-sensitive shortcuts without querying MC internals.
    // Volatile rather than render-thread-only: they are read from the tick and
    // the network-disconnect callback, which is exactly where the value has to
    // be visible immediately rather than one execute-hop later.
    @Volatile var onRankedScreen: Boolean = false
    @Volatile var onResultScreen: Boolean = false

    /** True while a match result is being waited on, so the poll only replaces
     *  the loading screen if the player hasn't navigated away. Distinct from
     *  [resultLoadingVisible]: this says the wait is still on, that one says
     *  whether the screen for it is actually up. */
    @Volatile var onResultLoading: Boolean = false

    /** True while [MatchResultLoadingScreen] is the screen on show. Cleared by
     *  its `removed()`, which is how the client tick learns that something —
     *  vanilla's disconnect screen — has taken the screen from us and puts it
     *  back. */
    @Volatile var resultLoadingVisible: Boolean = false

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
