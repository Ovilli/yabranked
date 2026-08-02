package dev.yabranked.client.replay

import com.mojang.authlib.GameProfile
import dev.yabranked.proto.ReplayPose
import dev.yabranked.proto.ReplayTrack
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.Entity
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Gives the recorded players bodies.
 *
 * A packet capture cannot contain the recorded player's own body: movement is
 * client-authoritative, so a server never sends a player their own position and
 * player N is the one player missing from stream N. Watching your own match back
 * showed the world you moved through and never you moving through it, and there
 * was nothing to teleport to, follow, or sit behind the eyes of.
 *
 * So the packets supply the world and [ReplayTrack] supplies the people. For each
 * recorded player this keeps a [RemotePlayer] in the level and moves it along that
 * player's track as the playhead advances.
 *
 * **A ghost stands down when the real thing is present.** The other player *is* in
 * the stream as a genuine entity whenever they were in the viewer's tracking
 * range, and drawing a ghost on top of them would be two of the same player a few
 * hundred milliseconds apart. The real entity always wins: it is the recording,
 * and the track is only an interpolation of it.
 */
class ReplayGhosts(private val tracks: List<ReplayTrack>) {
    private val log = LoggerFactory.getLogger("yabranked-replay")

    private val ghosts = HashMap<UUID, ReplayBody>()

    /** The level the current ghosts belong to; a seek backwards replaces it. */
    private var level: ClientLevel? = null

    /** Entity ids for bodies, counting down from the top away from the server's. */
    private var nextBodyId = Int.MAX_VALUE

    val isEmpty: Boolean get() = tracks.isEmpty()

    /** Every ghost currently in the world, for the camera to cycle through. */
    fun bodies(): List<ReplayBody> = ghosts.values.filter { it.isAlive }

    fun bodyOf(name: String): ReplayBody? = ghosts.values.firstOrNull { it.gameProfile.name == name }

    /**
     * Move every ghost to where its player was at [millis].
     *
     * Called from the client tick, after the frames for that tick have been fed, so
     * the bodies and the world they stand in are describing the same moment.
     */
    fun tick(millis: Int) {
        val minecraft = Minecraft.getInstance()
        val current = minecraft.level ?: return
        // A backwards seek tears the world down and builds a new one; the old
        // ghosts belong to a level that no longer exists.
        if (current !== level) {
            ghosts.clear()
            level = current
        }

        // Who the recording itself is drawing right now: every player in the level
        // that is neither one of ours nor the viewer. Those are real entities from
        // real packets and outrank an interpolated track every time.
        //
        // The viewer is excluded deliberately. The local player *is* the player
        // whose stream this is — an invisible spectator sitting wherever the last
        // teleport put them — so counting them as "already drawn" is what would
        // leave the one player you came to watch with no body at all.
        val self = minecraft.player
        val recorded = current.players()
            .filter { player -> player !== self && ghosts.values.none { it === player } }
            .map { it.gameProfile.id }
            .toSet()

        // Which world the viewer is standing in. A track runs across all of them —
        // a bingo match sends players to the Nether — and a pose is only about the
        // world it was sampled in: drawn anywhere else it is a body at Nether
        // coordinates standing in the Overworld, eight times too close to spawn.
        // `ReplayPose.dimension` was carried for exactly this and never read.
        val here = runCatching { current.dimension().identifier().toString() }.getOrDefault("")

        for (track in tracks) {
            val uuid = runCatching { UUID.fromString(track.player.uuid) }.getOrNull() ?: continue
            val pose = poseAt(track, millis)?.takeUnless { elsewhere(it, here) }
            val ghost = ghosts[uuid]

            if (pose == null || uuid in recorded) {
                ghost?.let { remove(current, it) }
                ghosts.remove(uuid)
                continue
            }

            val body = ghost
            if (body == null) {
                spawn(current, uuid, track.player.name)?.placeAt(pose)
                continue
            }
            // A body that is walking is aimed and animates its way there; one that
            // has jumped is placed. The two are told apart by distance because that
            // is what actually distinguishes them: a player moves a few tenths of a
            // block per tick, and only a seek moves them a hundred at once.
            if (body.distanceToSqr(pose.x, pose.y, pose.z) > JUMP_SQR) body.placeAt(pose)
            else body.aimAt(pose)
        }
    }

    /**
     * A body's uuid: derived from the player's, never equal to it.
     *
     * Sharing the real one would be better for skins — the recording's own tab list
     * has that entry — but a level indexes players by uuid, and the viewer *is* the
     * recorded player, so a body carrying their uuid would collide with the local
     * player in every lookup that resolves one. The same collision happens for a
     * heartbeat whenever an opponent walks back into tracking range and their real
     * entity arrives beside the ghost that has not been removed yet.
     *
     * The skin that would otherwise cost is handed back by [ReplayBody], which
     * looks it up with the player's real uuid instead of the entity's.
     */
    private fun bodyIdFor(uuid: UUID) = UUID(uuid.mostSignificantBits, uuid.leastSignificantBits xor GHOST_SALT)

    private fun spawn(level: ClientLevel, uuid: UUID, name: String): ReplayBody? = runCatching {
        val player = ReplayBody(level, GameProfile(bodyIdFor(uuid), name), realId = uuid)
        // Assigned before adding, because `ClientLevel.addEntity` reads it and a
        // fresh entity has none: vanilla always sets it from the AddEntity packet
        // that carried the entity, and there is no packet here. Counting *down*
        // from the top keeps these clear of the server's ids, which start at 1 and
        // climb — a collision would mean a recorded entity and a drawn body
        // fighting over the same slot in the level.
        player.id = nextBodyId--
        level.addEntity(player)
        ghosts[uuid] = player
        log.info("replay: spawned a body for {}", name)
        player
    }.onFailure { log.warn("replay: could not spawn a body for {}", name, it) }.getOrNull()

    private fun remove(level: ClientLevel, entity: Entity) {
        runCatching { level.removeEntity(entity.id, Entity.RemovalReason.DISCARDED) }
    }

    /**
     * The pose at [millis], interpolated through the samples around it.
     *
     * Null before the track starts or after it ends, which is how a player who
     * disconnected mid-match stops having a body rather than standing frozen where
     * they left.
     *
     * **The curve is cubic, not a straight line, and that is about the head.** The
     * track is sampled at [ReplayTrack.SAMPLE_HZ] — a fifth of a second — which is
     * ample for a walking body and coarse for a look direction, because where a
     * player is pointing is the fastest-changing thing about them. Joining those
     * samples with straight lines gives a curve that is continuous but has a corner
     * at every one of them, and riding a camera through a corner every 200ms is
     * exactly the jerk a follow cam shows. A Catmull-Rom through four samples has
     * no corners, so the camera sweeps rather than steps.
     *
     * It does not invent detail: a flick that happened between two samples was
     * never recorded and cannot be recovered here. It removes the artefact that
     * the sampling *adds*.
     */
    internal fun poseAt(track: ReplayTrack, millis: Int): ReplayPose? {
        val poses = track.poses
        if (poses.isEmpty()) return null
        if (millis < poses.first().atMillis || millis > poses.last().atMillis + STALE_MS) return null

        var low = 0
        var high = poses.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (poses[mid].atMillis < millis) low = mid + 1 else high = mid
        }
        val p2 = poses[low]
        val p1 = poses.getOrNull(low - 1) ?: return p2
        val span = (p2.atMillis - p1.atMillis).coerceAtLeast(1)
        val t = ((millis - p1.atMillis).toFloat() / span).coerceIn(0f, 1f)

        // One sample beyond each end, which is what a Catmull-Rom needs to know
        // which way the curve is already going. Repeating the endpoint at the ends
        // of the track is what makes it start and finish flat instead of leaving.
        val p0 = poses.getOrNull(low - 2) ?: p1
        val p3 = poses.getOrNull(low + 1) ?: p2

        // Uniform Catmull-Rom assumes evenly spaced samples, and the recorder does
        // sample on a fixed schedule — but a gap (a player who dropped and came
        // back, a server that stalled) breaks the assumption badly enough for the
        // curve to overshoot through geometry. An uneven neighbourhood falls back
        // to the straight line, whose worst case is the corner this replaces.
        if (!evenlySpaced(p0, p1, p2, p3, span)) {
            return p1.copy(
                x = lerp(p1.x, p2.x, t),
                y = lerp(p1.y, p2.y, t),
                z = lerp(p1.z, p2.z, t),
                yaw = p1.yaw + angleDelta(p1.yaw, p2.yaw) * t,
                pitch = p1.pitch + (p2.pitch - p1.pitch) * t,
            )
        }

        // Yaw is unwrapped into one continuous chain before it is interpolated:
        // 350° and 10° are twenty degrees apart, and a spline through the raw
        // numbers would sweep the body the other way round.
        val y1 = p1.yaw
        val y0 = y1 + angleDelta(y1, p0.yaw)
        val y2 = y1 + angleDelta(y1, p2.yaw)
        val y3 = y2 + angleDelta(p2.yaw, p3.yaw)

        return p1.copy(
            x = spline(p0.x, p1.x, p2.x, p3.x, t),
            y = spline(p0.y, p1.y, p2.y, p3.y, t),
            z = spline(p0.z, p1.z, p2.z, p3.z, t),
            yaw = spline(y0.toDouble(), y1.toDouble(), y2.toDouble(), y3.toDouble(), t).toFloat(),
            // Clamped because a spline overshoots at a sharp turn, and a pitch past
            // straight up is not a look, it is a body bent backwards.
            pitch = spline(
                p0.pitch.toDouble(), p1.pitch.toDouble(), p2.pitch.toDouble(), p3.pitch.toDouble(), t,
            ).toFloat().coerceIn(-90f, 90f),
        )
    }

    /**
     * Whether [pose] belongs to a different world than the one being watched.
     *
     * An empty dimension on either side is not an answer: recordings made before
     * the field was populated carry none, and refusing to draw those bodies would
     * turn a missing detail into a missing player.
     */
    private fun elsewhere(pose: ReplayPose, here: String): Boolean =
        pose.dimension.isNotEmpty() && here.isNotEmpty() && pose.dimension != here

    private fun lerp(from: Double, to: Double, t: Float): Double = from + (to - from) * t

    /** The Catmull-Rom through [p0]..[p3], evaluated between [p1] and [p2]. */
    private fun spline(p0: Double, p1: Double, p2: Double, p3: Double, t: Float): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (
            2 * p1 +
                (p2 - p0) * t +
                (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 +
                (3 * p1 - p0 - 3 * p2 + p3) * t3
            )
    }

    /** Whether the four samples are spaced closely enough to the same interval. */
    private fun evenlySpaced(
        p0: ReplayPose,
        p1: ReplayPose,
        p2: ReplayPose,
        p3: ReplayPose,
        span: Int,
    ): Boolean {
        val lead = p1.atMillis - p0.atMillis
        val trail = p3.atMillis - p2.atMillis
        // Zero means an end of the track, where the endpoint was repeated on
        // purpose; that is even by construction rather than a gap.
        return (lead == 0 || lead <= span * 2) && (trail == 0 || trail <= span * 2)
    }

    /** Shortest way round, so a body turning past 180° does not spin the long way. */
    private fun angleDelta(from: Float, to: Float): Float {
        var delta = (to - from) % 360f
        if (delta >= 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    fun clear() {
        val level = this.level ?: return
        ghosts.values.forEach { remove(level, it) }
        ghosts.clear()
    }

    private companion object {
        /**
         * How long a body lingers past its last sample before it is taken away. One
         * sampling gap plus slack: without it a body flickers out between samples at
         * the end of a track.
         */
        const val STALE_MS = 1_500

        /** Arbitrary but fixed: makes a body's uuid differ from its player's. */
        const val GHOST_SALT = 0x5941_4252_414E_4B44L

        /**
         * Past this much movement in one tick (8 blocks, squared), the body is
         * placed rather than walked. A sprinting player covers about half a block
         * a tick, and an ender pearl is the only ordinary thing that beats this —
         * which is a jump, and is meant to look like one.
         */
        const val JUMP_SQR = 64.0
    }
}
