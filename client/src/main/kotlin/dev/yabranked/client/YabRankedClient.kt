package dev.yabranked.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors

class YabRankedClient : ClientModInitializer {

    override fun onInitializeClient() {
        // "Ranked" button in the top-right corner of the title screen
        ScreenEvents.AFTER_INIT.register { _, screen, scaledWidth, _ ->
            if (screen is TitleScreen) {
                Screens.getWidgets(screen).add(
                    Button.builder(Component.literal("Ranked")) {
                        Minecraft.getInstance().setScreenAndShow(RankedScreen(screen))
                    }.bounds(scaledWidth - 64, 4, 60, 20).build()
                )
            }
        }

        // returning from a ranked match: refresh the profile to show the rating change
        ClientPlayConnectionEvents.DISCONNECT.register { _, client ->
            val match = RankedState.activeMatch ?: return@register
            val backend = RankedState.backend ?: return@register
            val before = RankedState.profile
            RankedState.activeMatch = null

            log.info("left ranked match ${match.matchId}; fetching result")
            workers.execute {
                // small grace period: the agent needs a moment to report the result
                Thread.sleep(3000)
                val updated = before?.let { backend.fetchProfile(it.uuid) }
                client.execute {
                    if (updated != null) {
                        if (before != null && updated.rating != before.rating) {
                            RankedState.lastRatingChange = updated.rating - before.rating
                        }
                        RankedState.profile = updated
                    }
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger("yabranked-client")

        val workers = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "yabranked-client").apply { isDaemon = true }
        }

        val backendUrl: String =
            System.getProperty("yabranked.url")
                ?: System.getenv("YABRANKED_URL")
                ?: "http://localhost:8080"

        val modVersion: String =
            FabricLoader.getInstance().getModContainer("yabranked-client")
                .map { it.metadata.version.friendlyString }
                .orElse("unknown")
    }
}
