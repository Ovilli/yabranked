package dev.yabranked.client.ui

import net.minecraft.util.Util
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.PlayerSkin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Draws player heads from their Minecraft skin.
 *
 * Skins resolve asynchronously through the vanilla skin manager, so lookups
 * are cached per UUID and a neutral placeholder is drawn until one arrives —
 * the UI never blocks or flickers waiting on the network.
 */
object PlayerHeads {

    private val cache = ConcurrentHashMap<UUID, PlayerSkin>()
    private val pending = ConcurrentHashMap.newKeySet<UUID>()

    private const val SKIN_TEXTURE_SIZE = 64
    private const val FACE_UV = 8f
    private const val HAT_UV = 40f
    private const val FACE_SIZE = 8

    /**
     * Resolved skin, or the vanilla default (Steve/Alex, chosen from the UUID)
     * while the lookup is in flight or when the account has no custom skin.
     *
     * **The profile has to be fetched before the skin manager is asked.**
     * `SkinManager.get` reads the `textures` property off the profile it is
     * handed and, finding none, resolves to `MinecraftProfileTextures.EMPTY` —
     * it never goes to Mojang itself. Handing it a bare `GameProfile(uuid, name)`
     * therefore always produced the default skin, which is why a signed-in
     * player's own face on their own profile card was Steve.
     */
    private fun skinFor(uuid: UUID): PlayerSkin {
        cache[uuid]?.let { return it }

        if (pending.add(uuid)) {
            val minecraft = Minecraft.getInstance()
            val sessionService = minecraft.services().sessionService()
            CompletableFuture
                .supplyAsync({ sessionService.fetchProfile(uuid, true)?.profile }, Util.backgroundExecutor())
                // back on the render thread: the skin manager registers textures
                // with the texture manager, which is not thread-safe
                .thenAcceptAsync({ profile ->
                    if (profile == null) {
                        // offline/dev accounts have no Mojang profile at all
                        pending.remove(uuid)
                        return@thenAcceptAsync
                    }
                    minecraft.skinManager.get(profile)
                        .thenAccept { skin ->
                            skin.ifPresent { cache[uuid] = it }
                            pending.remove(uuid)
                        }
                        .exceptionally {
                            pending.remove(uuid)
                            null
                        }
                }, minecraft)
                .exceptionally {
                    // a throttled or unreachable session service; keep the
                    // default and let the next screen that needs it try again
                    pending.remove(uuid)
                    null
                }
        }
        return DefaultPlayerSkin.get(uuid)
    }

    /**
     * Draws a [size]-pixel head at ([x], [y]) with a subtle frame. Falls back
     * to a flat silhouette while the skin is still loading.
     */
    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int, uuid: String, name: String, accent: Int) {
        val parsed = runCatching { UUID.fromString(uuid) }.getOrNull()

        // frame sits behind the head so every avatar has a consistent edge
        g.fill(x - 1, y - 1, x + size + 1, y + size + 1, accent)

        if (parsed == null) {
            g.fill(x, y, x + size, y + size, 0xFF1A1A1A.toInt())
            return
        }

        val texture: Identifier = skinFor(parsed).body().texturePath()

        // base face layer
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture,
            x, y,
            FACE_UV, FACE_UV,
            size, size,
            FACE_SIZE, FACE_SIZE,
            SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE,
        )
        // hat/overlay layer, so skins with hats render correctly
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture,
            x, y,
            HAT_UV, FACE_UV,
            size, size,
            FACE_SIZE, FACE_SIZE,
            SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE,
        )
    }
}
