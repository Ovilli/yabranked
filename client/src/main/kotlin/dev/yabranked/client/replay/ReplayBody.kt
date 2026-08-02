package dev.yabranked.client.replay

import com.mojang.authlib.GameProfile
import dev.yabranked.proto.ReplayPose
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.world.entity.player.PlayerSkin
import java.util.UUID

/**
 * The body of a recorded player, drawn from their position track.
 *
 * It exists because a packet capture cannot contain the recorded player's own
 * body — see [ReplayGhosts] — and it is a subclass rather than a plain
 * [RemotePlayer] for one reason: the skin.
 *
 * ## Why the entity's own uuid is the wrong one to ask with
 *
 * A body cannot carry its player's real uuid. A level indexes players by uuid and
 * the viewer *is* the recorded player, so a body holding that uuid collides with
 * the local player in every lookup that resolves one — and collides again, for a
 * heartbeat, whenever an opponent walks back into tracking range beside a ghost
 * that has not been removed yet. So bodies carry a derived id.
 *
 * That derived id is exactly what vanilla's `getSkin()` would look the skin up
 * with, and it matches nobody: the result is a default Steve or Alex wearing the
 * right name. Overriding the lookup to use [realId] fixes it at the source. The
 * recording carries the real tab list — a server sends every player's entry,
 * including the recipient's own — so the textures are already on this client, and
 * this is only a matter of asking about the right player.
 *
 * No mixin is needed: `AbstractClientPlayer.getSkin` is public and non-final, and
 * a subclass is both narrower and easier to be sure of than rewriting a method
 * every player entity in the game runs.
 *
 * ## Why the pose is applied from [tick] and not from the client tick
 *
 * A body used to be moved with `snapTo` once per client tick, and `snapTo` sets
 * the *old* position equal to the new one — that is what the name means. Two
 * things follow, and both were visible: the renderer interpolates between old and
 * current, so a body with those equal teleports twenty times a second instead of
 * gliding; and [net.minecraft.world.entity.LivingEntity.calculateEntityAnimation]
 * derives limb swing from exactly that delta, so it measured zero and the arms
 * and legs never moved. The bodies slid around the map in a fixed standing pose.
 *
 * The level ticks these entities like any other, and `ClientLevel.tickNonPassenger`
 * calls `setOldPosAndRot()` immediately before `tick()`. So the one moment at
 * which a new position produces both correct interpolation *and* correct animation
 * is inside `tick()`, after the old pose has been captured and before the
 * supertype derives anything from it. Setting the position from the client tick
 * instead — which is when the playhead moves — always lands outside that window.
 *
 * So the client tick only ever says where the body is *going* ([aimAt]), and the
 * body applies it itself at the moment vanilla expects a position to change.
 */
class ReplayBody(
    level: ClientLevel,
    profile: GameProfile,
    /** The uuid the player really had, which is what their skin is filed under. */
    private val realId: UUID,
) : RemotePlayer(level, profile) {

    /** Where the track says this player is; applied by [tick]. */
    private var pending: ReplayPose? = null

    /** Aim the body at [pose]; it moves there on its next tick, and animates doing it. */
    fun aimAt(pose: ReplayPose) {
        pending = pose
    }

    /**
     * Put the body at [pose] with no interpolation and no animation.
     *
     * For the first appearance and for a seek, where the two positions either side
     * of the jump are not a movement anybody made: interpolating across one draws
     * the body streaking over the map, and animating it sets the legs sprinting.
     */
    fun placeAt(pose: ReplayPose) {
        pending = null
        snapTo(pose.x, pose.y, pose.z, pose.yaw, pose.pitch)
        yHeadRot = pose.yaw
        yHeadRotO = pose.yaw
        yBodyRot = pose.yaw
        yBodyRotO = pose.yaw
        walkAnimation.stop()
    }

    override fun tick() {
        val pose = pending
        if (pose != null) {
            // Position and look before the supertype runs: `setOldPosAndRot` has
            // already captured the previous ones, so this is the delta everything
            // downstream — interpolation, limb swing, the bob — is derived from.
            setPos(pose.x, pose.y, pose.z)
            yRot = pose.yaw
            xRot = pose.pitch
        }
        super.tick()
        if (pose != null) {
            // Head and body yaw *after*, because LivingEntity.tick is what carries
            // their previous values into the O-fields the renderer interpolates
            // from. Set before the call and the head would snap instead of turning.
            yHeadRot = pose.yaw
            yBodyRot = pose.yaw
        }
    }

    override fun getSkin(): PlayerSkin {
        // The recording's own player list, asked with the recorded uuid.
        val info = Minecraft.getInstance().connection?.getPlayerInfo(realId)
        // Falls back on the *real* id rather than this entity's, so even a player
        // the tab list has forgotten gets the default skin their uuid implies —
        // the same one the live match would have shown — instead of one derived
        // from an id that never belonged to anybody.
        return info?.skin ?: DefaultPlayerSkin.get(realId)
    }
}
