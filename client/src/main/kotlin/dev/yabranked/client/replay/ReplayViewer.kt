package dev.yabranked.client.replay

import dev.yabranked.proto.MatchReplayMeta
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.gui.screens.Screen
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The one replay being watched, and the controls for it.
 *
 * Process-wide state rather than something a screen owns, because watching a
 * replay means *not* being on a screen: the player is standing in the recorded
 * world, and the thing driving playback has to survive every screen open and
 * close for as long as they are there. [tick] is called from the client tick and
 * [ReplayHud] draws the timeline.
 *
 * Only one at a time, and it is exclusive with being in a match: playback
 * replaces the client's connection, so a player cannot be in a recorded world and
 * a live one at once. Opening a replay from a live match is refused rather than
 * disconnecting somebody out of a ranked game they are playing.
 */
object ReplayViewer {
    private val log = LoggerFactory.getLogger("yabranked-replay")

    val cache: ReplayCache by lazy { ReplayCache.default() }

    var playback: ReplayPlayback? = null
        private set

    /** The screen to go back to when the replay is closed. */
    private var parent: Screen? = null

    /** Bodies for the recorded players; see [ReplayGhosts]. */
    private var ghosts: ReplayGhosts? = null

    val isWatching: Boolean get() = playback != null

    val meta: MatchReplayMeta? get() = playback?.meta

    /**
     * Start playing a downloaded recording from [dir]. Returns whether it opened;
     * the caller is on the render thread and shows the failure itself.
     */
    fun open(meta: MatchReplayMeta, dir: Path, primaryIndex: Int, parent: Screen?): Boolean {
        close()
        val playback = ReplayPlayback.open(meta, dir, primaryIndex, parent) ?: return false
        this.parent = parent
        this.playback = playback
        this.ghosts = ReplayGhosts(meta.tracks)
        playback.start()
        // Straight to the match. Everything before it is the configuration
        // handshake and the lobby, which nobody opened a replay to watch.
        playback.seekToGameStart()
        // Open the controls with it. A panel reachable only by knowing a key is
        // barely better than the keys it replaced — the first thing a viewer
        // should see is the thing they can press, and closing it is how you get
        // the world to yourself.
        Minecraft.getInstance().execute { Minecraft.getInstance().setScreenAndShow(ReplayControlsScreen()) }
        return true
    }

    /** Re-open the same recording from another player's field of view. */
    fun switchPerspective(index: Int) {
        val current = playback ?: return
        val at = current.positionMillis
        val meta = current.meta
        val dir = cache.dirOf(meta.matchId)
        val parent = this.parent
        if (!open(meta, dir, index, parent)) {
            log.warn("could not switch to replay stream {}", index)
            return
        }
        playback?.seek(at)
    }

    /**
     * The player the camera is riding, or null for the free camera.
     *
     * Held as a *name* rather than an entity because the entity is destroyed and
     * rebuilt every time playback restarts — which a backwards seek does — and a
     * camera holding a dead entity is a black screen.
     */
    var following: String? = null
        private set

    /** True once the last frame has been fed; playback holds here rather than looping. */
    val ended: Boolean get() = playback?.isOver == true

    fun tick() {
        val playback = playback ?: return
        playback.tick()
        // Re-asserted every tick rather than once at the start: the recording
        // contains the game mode the player actually had, and any packet that
        // sets it again would otherwise put the viewer back into survival.
        playback.forceSpectator()
        // The end is a stop, not a loop and not an exit. Exiting on its own would
        // yank someone out of a world they may still want to fly around, and the
        // last frame is often the most interesting one.
        if (playback.isOver && !playback.paused) playback.paused = true
        // After the frames, so bodies and the world they stand in describe the same
        // moment rather than being a tick apart.
        ghosts?.tick(playback.positionMillis)
        reattachCamera()
    }

    /**
     * Everyone visible in the recorded world right now.
     *
     * The local player is excluded and that is not a detail: in stream N the local
     * player *is* the recorded player, and a server never sends a player their own
     * position, so their body does not exist in their own recording. Watching
     * through someone is watching from behind their eyes, never at them — to see a
     * player, watch a stream somebody else received.
     */
    fun visiblePlayers(): List<AbstractClientPlayer> {
        val minecraft = Minecraft.getInstance()
        val self = minecraft.player ?: return emptyList()
        return minecraft.level?.players().orEmpty()
            .filter { it !== self }
            .distinctBy { it.gameProfile.name }
            .sortedBy { it.gameProfile.name }
    }

    /** The body of a recorded player, if this recording draws one for them. */
    fun bodyOf(name: String): ReplayBody? = ghosts?.bodyOf(name)

    /** Whether this recording carries the tracks that bodies are drawn from. */
    val hasBodies: Boolean get() = ghosts?.isEmpty == false

    /**
     * Free camera → each visible player in turn → free camera.
     *
     * Riding a player puts the view exactly where theirs was, which is the closest
     * thing a recording has to a first-person seat. Leaving it hands the camera
     * back with the player's position kept, so you carry on flying from where they
     * were standing rather than being thrown back across the map.
     */
    /** Follow [name], or stop following if that is who is already being followed. */
    fun toggleFollow(name: String) {
        val minecraft = Minecraft.getInstance()
        if (following == name) {
            following = null
            minecraft.player?.let { minecraft.setCameraEntity(it) }
            return
        }
        following = name
        reattachCamera()
    }

    /** Put the free camera where [name] is standing, without riding them. */
    fun teleportTo(name: String) {
        val minecraft = Minecraft.getInstance()
        val self = minecraft.player ?: return
        val target = visiblePlayers().firstOrNull { it.gameProfile.name == name } ?: return
        self.snapTo(target.x, target.y, target.z, target.yRot, target.xRot)
        following = null
        minecraft.setCameraEntity(self)
    }

    fun cycleCamera() {
        val minecraft = Minecraft.getInstance()
        val players = visiblePlayers()
        if (players.isEmpty()) {
            following = null
            minecraft.player?.let { minecraft.setCameraEntity(it) }
            return
        }
        val current = players.indexOfFirst { it.gameProfile.name == following }
        val next = players.getOrNull(current + 1)
        following = next?.gameProfile?.name
        if (next == null) {
            // Back to free flight, standing where the last followed player was.
            minecraft.player?.let { self ->
                players.getOrNull(current)?.let { self.snapTo(it.x, it.y, it.z, it.yRot, it.xRot) }
                minecraft.setCameraEntity(self)
            }
        } else {
            minecraft.setCameraEntity(next)
        }
    }

    /** Move the free camera to whoever is being followed, without riding them. */
    fun teleportToFollowed() {
        val minecraft = Minecraft.getInstance()
        val self = minecraft.player ?: return
        val target = visiblePlayers().firstOrNull { it.gameProfile.name == following }
            ?: visiblePlayers().firstOrNull()
            ?: return
        self.snapTo(target.x, target.y, target.z, target.yRot, target.xRot)
        minecraft.setCameraEntity(self)
        following = null
    }

    /**
     * Keep the camera on the followed player across the entity churn of playback.
     *
     * Entities leave and re-enter tracking range constantly, and a backwards seek
     * destroys the world entirely. Re-resolving by name each tick means the camera
     * survives all of it, and falls back to free flight rather than to a black
     * screen when the player is genuinely gone.
     */
    private fun reattachCamera() {
        val name = following ?: return
        val minecraft = Minecraft.getInstance()
        val self = minecraft.player ?: return
        val target = visiblePlayers().firstOrNull { it.gameProfile.name == name }
        if (target == null) {
            if (minecraft.cameraEntity !== self) minecraft.setCameraEntity(self)
            return
        }
        if (minecraft.cameraEntity !== target) minecraft.setCameraEntity(target)
    }

    fun togglePause() {
        playback?.let { it.paused = !it.paused }
    }

    fun nudge(millis: Int) {
        playback?.let { it.seek(it.positionMillis + millis) }
    }

    fun cycleSpeed() {
        val playback = playback ?: return
        val next = SPEEDS.firstOrNull { it > playback.speed + 0.01f } ?: SPEEDS.first()
        playback.speed = next
    }

    /** Marked moments, as millis into the recording. */
    private fun eventMillis(): List<Int> {
        val playback = playback ?: return emptyList()
        return playback.meta.events
            .map { (playback.meta.gameStartMillis + it.atSeconds * 1000).toInt() }
            .sorted()
    }

    /** Whether this recording has a timeline to jump around at all. */
    val hasEvents: Boolean get() = playback?.meta?.events?.isNotEmpty() == true

    /**
     * Jump to the next or previous marked moment.
     *
     * The nudge is because a seek lands exactly on the marker it just jumped to,
     * and without it "next" would find the same one forever.
     */
    fun jumpEvent(forward: Boolean) {
        val playback = playback ?: return
        val at = playback.positionMillis
        val target =
            if (forward) eventMillis().firstOrNull { it > at + 250 }
            else eventMillis().lastOrNull { it < at - 250 }
        if (target != null) playback.seek(target)
    }

    fun close() {
        val playback = playback ?: return
        this.playback = null
        following = null
        ghosts?.clear()
        ghosts = null
        playback.close()
        val parent = this.parent
        this.parent = null
        Minecraft.getInstance().execute {
            // Handed back before the world goes: a camera left riding an entity of
            // a level that is being torn down outlives the thing it is looking at.
            val minecraft = Minecraft.getInstance()
            minecraft.player?.let { minecraft.setCameraEntity(it) }
            if (parent != null) minecraft.setScreenAndShow(parent)
        }
    }

    /** The speeds the cycle button walks through. */
    val SPEEDS = floatArrayOf(0.25f, 0.5f, 1f, 2f, 4f, 8f)
}
