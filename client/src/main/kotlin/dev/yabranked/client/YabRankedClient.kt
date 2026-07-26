package dev.yabranked.client

import com.mojang.blaze3d.platform.InputConstants
import dev.yabranked.client.ui.IconButton
import dev.yabranked.client.ui.QueueBadge
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.MatchHistoryEntry
import dev.yabranked.proto.PlayerProfile
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

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
                val uuid = before?.uuid ?: return@execute
                val poll = pollForResult(backend, uuid, match.matchId)

                client.execute {
                    val after = poll.profile
                    val e = poll.entry
                    RankedState.winStreak = RankedState.currentWinStreak(poll.history)
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

    /** What [pollForResult] managed to gather about a just-finished match. */
    private class ResultPoll(
        val profile: PlayerProfile?,
        val entry: MatchHistoryEntry?,
        val history: List<MatchHistoryEntry>,
    )

    /**
     * Wait for the finished match to show up in the player's history.
     *
     * The backend only records the result as the game ends, so it is usually
     * not persisted yet at the moment the client is disconnected. Wait once,
     * then retry on a short interval and **return as soon as the entry lands**
     * — worst case 1.5 s + 7 × 1.5 s ≈ 12 s of waiting. Fetch enough history to
     * also derive the win streak.
     *
     * Blocking; call from [workers], never the render thread.
     */
    private fun pollForResult(backend: BackendClient, uuid: String, matchId: String): ResultPoll {
        val attempts = 8
        val delayMs = 1500L
        var profile: PlayerProfile? = null
        var history: List<MatchHistoryEntry> = emptyList()

        Thread.sleep(delayMs)
        for (attempt in 0 until attempts) {
            // Keep the last profile we did get: a single flaky request should not
            // cost us the rating delta we already read.
            profile = backend.fetchProfile(uuid).orElse(profile)
            history = backend.fetchHistory(uuid, limit = 20).orElse(history)
            val entry = history.firstOrNull { it.matchId == matchId }
            if (entry != null) return ResultPoll(profile, entry, history)
            if (attempt < attempts - 1) Thread.sleep(delayMs)
        }
        return ResultPoll(profile, null, history)
    }

    companion object {
        const val MOD_ID = "yabranked-client"
        private val log = LoggerFactory.getLogger("yabranked-client")

        private val workerId = AtomicInteger()

        /**
         * Pool for every blocking [BackendClient] call, plus the delayed queue
         * reconnects in [RankedQueue].
         *
         * Deliberately more than one thread: the post-match result poll blocks
         * its worker for ~12 s, and on a single-thread executor that stalled
         * every unrelated call queued behind it (Queue again, profile loads).
         * Small and fixed because the backend traffic is a handful of requests
         * per screen, not a throughput problem.
         */
        val workers: ScheduledExecutorService = Executors.newScheduledThreadPool(3) { runnable ->
            val id = workerId.incrementAndGet()
            Thread(runnable, "yabranked-client-$id").apply { isDaemon = true }
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
