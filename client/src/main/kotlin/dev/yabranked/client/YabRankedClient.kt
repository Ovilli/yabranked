package dev.yabranked.client

import com.mojang.blaze3d.platform.InputConstants
import dev.yabranked.client.ui.IconButton
import dev.yabranked.client.ui.QueueBadge
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.MatchHistoryEntry
import dev.yabranked.proto.PlayerProfile
import dev.yabranked.proto.QueueServerMessage
import net.fabricmc.api.ClientModInitializer
import dev.yabranked.client.replay.ReplayViewer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.CommonComponents
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

    /** Ticks until the next [reconcileActiveMatch] check, and its in-flight guard. */
    private var reconcileCooldown = RECONCILE_INTERVAL_TICKS
    private var reconcileInFlight = false

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

        // In a ranked match, "Disconnect" is a lie by omission: leaving forfeits
        // the game on the spot. The button is replaced in place — same row, same
        // size — with one that says so and routes through the confirmation,
        // rather than left to look like an ordinary way out of a server.
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            if (screen !is PauseScreen) return@register
            val match = RankedState.activeMatch ?: return@register
            val widgets = Screens.getWidgets(screen)
            // Matched against the shared component rather than a label string:
            // the pause menu says "Disconnect" on a server and "Save and Quit
            // to Title" in single-player, and both come from here.
            val index = widgets.indexOfFirst {
                it is Button &&
                    (it.message == CommonComponents.GUI_DISCONNECT || it.message == CommonComponents.GUI_TO_TITLE)
            }
            if (index < 0) return@register
            val old = widgets[index]
            widgets[index] = RankedButton(
                old.x, old.y, old.width, old.height,
                Component.literal("§cForfeit this match"), Ui.ICON_LOSS,
            ) {
                client.setScreenAndShow(ForfeitConfirmScreen(screen, match.opponent.name))
            }.apply {
                setTooltip(
                    Tooltip.create(Component.literal("Leaving a ranked match concedes it. This asks first."))
                )
            }
        }

        // Queue badge and the notice stack, on every screen.
        //
        // Both live in the top-right, and the stack is drawn from here rather
        // than by each screen for itself — that is what stops two of them, or a
        // screen and the toast manager, painting the same corner twice. The
        // stack starts below the badge whenever the badge is up, so the two
        // never share a row.
        ScreenEvents.AFTER_INIT.register { client, screen, _, _ ->
            val ownsBadge = screen is RankedScreen || screen is MatchFoundScreen
            ScreenEvents.afterExtract(screen).register { _, g, mouseX, mouseY, _ ->
                val font = client.font
                // The game's own width, not the screen's: a ScaledScreen lays
                // itself out in a wider virtual viewport, and this draws outside
                // that screen's transform — off the right edge if it used it.
                val screenWidth = client.window.guiScaledWidth
                var top = 4
                if (QueueBadge.isVisible() && !ownsBadge) {
                    QueueBadge.draw(g, font, screenWidth - QueueBadge.width(font) - 4, top)
                    top += QueueBadge.HEIGHT + 4
                }
                RankedNotice.draw(g, font, screenWidth, top, mouseX, mouseY)
            }
            // Only the dismiss button consumes a click; everything else falls
            // through to the screen, which is still what the player is using.
            ScreenMouseEvents.allowMouseClick(screen).register { _, event ->
                !RankedNotice.clickedAt(event.x(), event.y())
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
            // Keep the party socket up for as long as there is a session.
            //
            // It used to be opened only by the login button, which meant a
            // player who signed in during an earlier screen — or whose socket
            // had dropped — simply had no channel open, and party invites and
            // friend requests aimed at them went nowhere. Both are pushes: with
            // no socket there is nothing to push to, and the sender sees an
            // invite that is silently never delivered. `connect()` is a no-op
            // when one is already open or being retried.
            if (RankedState.isAuthenticated) RankedParty.connect()

            // Age out the notice banner here rather than while drawing it: a
            // notice pushed with no ranked screen open would otherwise still be
            // waiting, unexpired, whenever one is next opened.
            RankedNotice.tick()

            // The one thing that can tell a client in the menus that its match
            // is over; see the function for why the disconnect event cannot.
            reconcileActiveMatch(client)

            // Drive playback from here, on the render thread. That is not
            // incidental: `PacketUtils.ensureRunningOnSameThread` then applies
            // each recorded packet inline instead of deferring it, so the world
            // never sees two ticks' worth of deltas in one frame.
            if (ReplayViewer.isWatching) ReplayViewer.tick()

            // Hold the result-loading screen for as long as the result is
            // actually being waited on. This callback runs after vanilla has set
            // its disconnect / server-list screen, so re-showing ours here
            // overrides it before the frame renders.
            //
            // Driven by the screen's own `removed()` rather than a tick budget:
            // there is no public getter for the current screen in this mapping,
            // and the old fixed five-tick window simply lost the race whenever a
            // container teardown was slow — the player got vanilla's
            // "Disconnected" instead of their own result.
            if (RankedState.onResultLoading && !RankedState.resultLoadingVisible) {
                client.setScreenAndShow(
                    MatchResultLoadingScreen(RankedState.lastMatch?.opponent?.name ?: "opponent")
                )
            }

            // Global hotkey: open Ranked from anywhere. On the result screen,
            // reuse R to trigger Queue Again and go to Ranked.
            while (openRankedKey.consumeClick()) {
                // While watching, the ranked key opens the replay's own controls.
                // They are buttons on a panel rather than a page of bindings, and
                // this is the one key that has to exist to reach them.
                if (ReplayViewer.isWatching) {
                    client.setScreenAndShow(dev.yabranked.client.replay.ReplayControlsScreen())
                } else if (RankedState.onResultScreen) {
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
            // matchEnded claims the screen synchronously, so the tick guard is
            // already asserting it before vanilla's disconnect screen can paint.
            matchEnded(client, match, "left ranked match")
        }
    }

    /**
     * Ask the backend whether the match we think we are in is still running.
     *
     * Only while sitting in the menus with a match on the books, which is the
     * one state the disconnect event cannot describe: we were never connected
     * to that server, so leaving it will never fire. Without this the client
     * stays wedged on a finished match forever — no queue button, no result,
     * just a Forfeit for something already decided.
     *
     * A failed or offline fetch is explicitly not an answer. Reading a flaky
     * request as "your match is over" would cancel a match the player is about
     * to be sent into.
     */
    private fun reconcileActiveMatch(client: Minecraft) {
        if (reconcileInFlight) return
        val match = RankedState.activeMatch ?: return
        val backend = RankedState.backend ?: return
        if (client.level != null) return
        if (--reconcileCooldown > 0) return
        reconcileCooldown = RECONCILE_INTERVAL_TICKS
        reconcileInFlight = true

        workers.execute {
            val live = backend.fetchLiveMatch()
            client.execute {
                reconcileInFlight = false
                // Still the same match we asked about? The player may have been
                // sent into a new one while this was in flight.
                if (RankedState.activeMatch?.matchId != match.matchId) return@execute
                if (live !is BackendClient.Fetch.Ok) return@execute
                if (live.value?.matchId == match.matchId) return@execute
                log.info("backend says match ${match.matchId} is over; winding it down")
                matchEnded(client, match, "match ended without us")
            }
        }
    }

    companion object {
        const val MOD_ID = "yabranked-client"
        private val log = LoggerFactory.getLogger("yabranked-client")

        /**
         * Five seconds between "is my match still running" checks.
         *
         * Only ever runs while the player is in the menus believing they are in
         * a match, which is rare and always transient, so this is a handful of
         * requests and never a poll loop in the ordinary case.
         */
        private const val RECONCILE_INTERVAL_TICKS = 100

        /**
         * Wind down a match this client is no longer in: clear the live state, then
         * go and find out what the result was.
         *
         * Reached three ways — being disconnected from the match server, the
         * backend telling us the match is over while we sit in the menus, and
         * conceding from the menus ourselves. They have to do the same thing,
         * because a player whose match ended without them (a teammate
         * forfeiting, a server that never came up) is the one who most needs the
         * result screen and least likely to get a disconnect event.
         *
         * Claims the loading screen itself, so no caller can wind a match down
         * and leave the player with nothing to look at.
         */
        fun matchEnded(client: Minecraft, match: QueueServerMessage.MatchFound, why: String) {
            RankedState.onResultLoading = true
            RankedState.resultLoadingVisible = false
            val before = RankedState.profile
            // Cleared before anything can fail: a client that still believes it is
            // in a finished match will not let the player queue for another one,
            // and offers them only "Forfeit" for a match that is already decided.
            RankedState.activeMatch = null
            RankedState.matchStartedAt = null
            RankedState.lastMatch = match
            RankedState.lastMatchReported = false

            val backend = RankedState.backend
            if (backend == null || before == null) {
                // Signed out mid-match. Nothing to poll with, but the state above is
                // already consistent, which is the part that used to be skipped by
                // returning early from here.
                RankedState.onResultLoading = false
                return
            }

            log.info("$why ${match.matchId}; fetching result")

            workers.execute {
                val poll = pollForResult(backend, before.uuid, match.matchId)

                client.execute {
                    val after = poll.profile
                    val e = poll.entry
                    RankedState.winStreak = RankedState.currentWinStreak(poll.history)
                    // Only replace the loading screen if the player is still on it
                    // — they may have hit Skip, or opened another screen.
                    val onLoading = RankedState.onResultLoading
                    if (after != null) {
                        if (after.rating != before.rating) {
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

        /**
         * The live backend, overridable for development.
         *
         * The default is the published service rather than localhost, because a
         * shipped jar is the case that has to work without configuration: a
         * player installing the mod has no `-Dyabranked.url` to set and no reason
         * to know one exists. Development is the case that *can* be asked to
         * configure itself, so `runClient` passes the override instead.
         *
         * The property is checked before the environment variable so a single
         * launch can point somewhere else without touching the shell it inherits.
         */
        const val DEFAULT_BACKEND_URL = "https://yabranked.ovilli.de"

        val backendUrl: String =
            System.getProperty("yabranked.url")
                ?: System.getenv("YABRANKED_URL")
                ?: DEFAULT_BACKEND_URL

        val modVersion: String =
            FabricLoader.getInstance().getModContainer(MOD_ID)
                .map { it.metadata.version.friendlyString }
                .orElse("unknown")
    }
}
