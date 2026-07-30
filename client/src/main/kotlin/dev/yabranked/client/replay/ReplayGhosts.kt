package dev.yabranked.client.replay

import com.mojang.authlib.GameProfile
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

        for (track in tracks) {
            val uuid = runCatching { UUID.fromString(track.player.uuid) }.getOrNull() ?: continue
            val pose = poseAt(track, millis)
            val ghost = ghosts[uuid]

            if (pose == null || uuid in recorded) {
                ghost?.let { remove(current, it) }
                ghosts.remove(uuid)
                continue
            }

            val body = ghost ?: spawn(current, uuid, track.player.name) ?: continue
            body.snapTo(pose.x, pose.y, pose.z, pose.yaw, pose.pitch)
            body.yHeadRot = pose.yaw
            body.yBodyRot = pose.yaw
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
     * The pose at [millis], interpolated between the two samples around it.
     *
     * Null before the track starts or after it ends, which is how a player who
     * disconnected mid-match stops having a body rather than standing frozen where
     * they left.
     */
    private fun poseAt(track: ReplayTrack, millis: Int): dev.yabranked.proto.ReplayPose? {
        val poses = track.poses
        if (poses.isEmpty()) return null
        if (millis < poses.first().atMillis || millis > poses.last().atMillis + STALE_MS) return null

        var low = 0
        var high = poses.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (poses[mid].atMillis < millis) low = mid + 1 else high = mid
        }
        val after = poses[low]
        val before = poses.getOrNull(low - 1) ?: return after
        val span = (after.atMillis - before.atMillis).coerceAtLeast(1)
        val t = ((millis - before.atMillis).toFloat() / span).coerceIn(0f, 1f)
        return before.copy(
            x = before.x + (after.x - before.x) * t,
            y = before.y + (after.y - before.y) * t,
            z = before.z + (after.z - before.z) * t,
            yaw = before.yaw + angleDelta(before.yaw, after.yaw) * t,
            pitch = before.pitch + (after.pitch - before.pitch) * t,
        )
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
    }
}
