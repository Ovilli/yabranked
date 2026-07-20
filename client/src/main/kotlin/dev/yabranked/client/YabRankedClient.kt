package dev.yabranked.client

import com.mojang.blaze3d.platform.InputConstants
import dev.yabranked.client.ui.IconButton
import dev.yabranked.client.ui.QueueBadge
import dev.yabranked.client.ui.Ui
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

class YabRankedClient : ClientModInitializer {

    private lateinit var openRankedKey: KeyMapping

    override fun onInitializeClient() {
        // "Ranked" button in the top-right corner of the title screen
        ScreenEvents.AFTER_INIT.register { _, screen, scaledWidth, _ ->
            if (screen is TitleScreen) {
                Screens.getWidgets(screen).add(
                    IconButton(
                        x = scaledWidth - 44,
                        y = 4,
                        // large enough that the RANKED wordmark still resolves
                        size = 40,
                        sprite = Ui.logo(),
                        sourceSize = 64,
                        label = Component.literal("YAB Ranked"),
                    ) {
                        Minecraft.getInstance().setScreenAndShow(RankedScreen(screen))
                    }
                )
            }
        }

        // Queue badge on every screen: leaving the ranked menu keeps the queue
        // running, so the player needs to see that from wherever they are.
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen is RankedScreen || screen is MatchFoundScreen) return@register
            ScreenEvents.afterExtract(screen).register { drawn, g, _, _, _ ->
                if (QueueBadge.isVisible()) {
                    val font = client.font
                    QueueBadge.draw(g, font, drawn.width - QueueBadge.width(font) - 4, 4)
                }
            }
        }

        // in-match overlay, drawn under the chat so it never covers messages
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath(MOD_ID, "ranked_hud"),
            RankedHud(),
        )

        openRankedKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.yabranked.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KeyMapping.Category.MISC,
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openRankedKey.consumeClick()) {
                // null parent: closing returns to the game rather than a menu
                client.setScreenAndShow(RankedScreen(null))
            }
        }

        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            if (RankedState.activeMatch != null) {
                RankedState.matchStartedAt = System.currentTimeMillis()
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            val match = RankedState.activeMatch ?: return@register
            val backend = RankedState.backend ?: return@register
            val before = RankedState.profile
            RankedState.activeMatch = null
            RankedState.matchStartedAt = null
            RankedState.lastMatch = match
            RankedState.lastMatchReported = false

            log.info("left ranked match ${match.matchId}; fetching result")
            workers.execute {
                // the agent reports the result as the game ends; give it a moment
                Thread.sleep(3000)
                val uuid = before?.uuid ?: return@execute
                val updated = backend.fetchProfile(uuid)
                val entry = backend.fetchHistory(uuid, limit = 5)
                    .firstOrNull { it.matchId == match.matchId }

                client.execute {
                    if (updated != null) {
                        if (before != null && updated.rating != before.rating) {
                            RankedState.lastRatingChange = updated.rating - before.rating
                        }
                        RankedState.profile = updated

                        if (entry != null) {
                            client.setScreenAndShow(MatchResultScreen(entry, before, updated))
                        } else {
                            // result not in yet — say so instead of silently doing nothing
                            RankedToast.show(
                                "Ranked",
                                "Result still processing…",
                                Ui.TEXT_DIM,
                            )
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val MOD_ID = "yabranked-client"
        private val log = LoggerFactory.getLogger("yabranked-client")

        val workers = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "yabranked-client").apply { isDaemon = true }
        }

        val backendUrl: String =
            System.getProperty("yabranked.url")
                ?: System.getenv("YABRANKED_URL")
                ?: "http://localhost:8080"

        val modVersion: String =
            FabricLoader.getInstance().getModContainer(MOD_ID)
                .map { it.metadata.version.friendlyString }
                .orElse("unknown")
    }
}
