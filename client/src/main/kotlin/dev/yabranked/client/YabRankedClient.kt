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
    private lateinit var joinQueueKey: KeyMapping
    private lateinit var leaveQueueKey: KeyMapping
    private lateinit var historyKey: KeyMapping

    /** Ticks left to force-claim the result-loading screen after a match ends,
     *  overriding vanilla's disconnect / server-list screen before it renders. */
    private var resultClaimTicks = 0

    override fun onInitializeClient() {
        Config.load()

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

        // Screen-local shortcuts (active only when the relevant screen is open)
        joinQueueKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.yabranked.join",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                KeyMapping.Category.MISC,
            )
        )
        leaveQueueKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.yabranked.leave",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                KeyMapping.Category.MISC,
            )
        )
        historyKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping(
                "key.yabranked.history",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KeyMapping.Category.MISC,
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            // Force-claim the result-loading screen for a few ticks after a match
            // ends. This END_CLIENT_TICK callback runs after vanilla has set its
            // disconnect / server-list screen, so re-showing ours here overrides
            // it before the frame renders — no flash. (No public getter for the
            // current screen in this mapping, so we claim for a short window
            // rather than testing what is currently shown.)
            if (resultClaimTicks > 0 && RankedState.onResultLoading) {
                resultClaimTicks--
                client.setScreenAndShow(
                    MatchResultLoadingScreen(RankedState.lastMatch?.opponent?.name ?: "opponent")
                )
            }

            // Global hotkey: open Ranked from anywhere. On the result screen,
            // reuse R to trigger Queue Again and go to Ranked.
            while (openRankedKey.consumeClick()) {
                if (RankedState.onResultScreen) {
                    if (!RankedState.isQueued && RankedState.isAuthenticated) {
                        RankedQueue.join()
                    }
                    client.setScreenAndShow(RankedScreen(TitleScreen()))
                } else {
                    // null parent: closing returns to the game rather than a menu
                    client.setScreenAndShow(RankedScreen(null))
                }
            }

            if (RankedState.onRankedScreen) {
                while (joinQueueKey.consumeClick()) {
                    if (!RankedState.isQueued) {
                        if (RankedState.isAuthenticated) {
                            RankedQueue.join()
                        } else {
                            RankedToast.showInfo("Can't join", "Please sign in first")
                        }
                    }
                }
                while (leaveQueueKey.consumeClick()) {
                    if (RankedState.isQueued) RankedQueue.leave()
                }
                while (historyKey.consumeClick()) {
                    // Open history with a fresh Ranked screen as parent for a clean back path
                    client.setScreenAndShow(MatchHistoryScreen(RankedScreen(null)))
                }
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
            // Set synchronously so the tick guard starts re-asserting our screen
            // this very tick — before vanilla's disconnect screen can paint. The
            // guard claims the screen for the next several ticks.
            RankedState.onResultLoading = true
            resultClaimTicks = 5

            log.info("left ranked match ${match.matchId}; fetching result")

            workers.execute {
                // The backend reports the result as the game ends; it may take
                // a short moment to be persisted and appear in history. We poll
                // briefly so we can show the result screen right away instead of
                // flashing a toast and doing nothing.
                val uuid = before?.uuid ?: return@execute
                var updated: WireProfile? = null
                var entry: WireHistoryEntry? = null
                var history: List<WireHistoryEntry> = emptyList()

                // Initial wait to let the agent report, then poll up to ~12s
                // total with short intervals. Stops early as soon as entry
                // appears. Fetch enough history to also derive the win streak.
                val attempts = 8
                val delayMs = 1500L
                Thread.sleep(1500)
                repeat(attempts) { _ ->
                    updated = backend.fetchProfile(uuid) ?: updated
                    history = backend.fetchHistory(uuid, limit = 20)
                    entry = history.firstOrNull { it.matchId == match.matchId }
                    if (entry != null) return@repeat
                    Thread.sleep(delayMs)
                }

                client.execute {
                    val after = updated
                    val e = entry
                    RankedState.winStreak = RankedState.currentWinStreak(history)
                    // Only replace the loading screen if the player is still on it
                    // — they may have hit Skip, or opened another screen.
                    val onLoading = RankedState.onResultLoading
                    if (after != null) {
                        if (before != null && after.rating != before.rating) {
                            RankedState.lastRatingChange = after.rating - before.rating
                        }
                        RankedState.profile = after
                    }
                    RankedState.onResultLoading = false
                    when {
                        after != null && e != null ->
                            if (onLoading) client.setScreenAndShow(MatchResultScreen(e, before, after))
                        else -> {
                            // Result never landed in time: don't strand the player
                            // on the loading screen — return to the title and note it.
                            if (onLoading) client.setScreenAndShow(TitleScreen())
                            RankedToast.show("Ranked", "Result still processing…", Ui.TEXT_DIM)
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
