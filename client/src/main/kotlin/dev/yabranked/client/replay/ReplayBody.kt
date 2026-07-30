package dev.yabranked.client.replay

import com.mojang.authlib.GameProfile
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
 */
class ReplayBody(
    level: ClientLevel,
    profile: GameProfile,
    /** The uuid the player really had, which is what their skin is filed under. */
    private val realId: UUID,
) : RemotePlayer(level, profile) {

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
