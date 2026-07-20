package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component

class RankedScreen(
    private val parent: Screen?,
) : Screen(Component.literal("YAB Ranked")) {

    private var queueButton: Button? = null

    /** Rebuilds the widgets in place; keeps the screen instance and its state. */
    private fun refresh() = rebuildWidgets()

    override fun init() {
        val centerX = width / 2
        val cardBottom = CARD_TOP + CARD_HEIGHT

        // while a match is live the menu's job is the match, not the queue
        val liveMatch = RankedState.activeMatch
        if (liveMatch != null) {
            addRenderableWidget(
                Button.builder(Component.literal("§cForfeit match")) {
                    minecraft.setScreenAndShow(ForfeitConfirmScreen(this, liveMatch.opponent.name))
                }.bounds(centerX - 100, cardBottom + 16, 200, 20).build()
            )
            addRenderableWidget(
                Button.builder(Component.literal("Back")) { onClose() }
                    .bounds(centerX - 100, height - 28, 200, 20)
                    .build()
            )
            return
        }

        if (!RankedState.isAuthenticated) {
            addRenderableWidget(
                Button.builder(Component.literal("Log in with Minecraft")) { login() }
                    .bounds(centerX - 100, cardBottom + 24, 200, 20)
                    .build()
            )
        } else {
            queueButton = addRenderableWidget(
                Button.builder(queueLabel()) { toggleQueue() }
                    .bounds(centerX - 100, cardBottom + 16, 200, 20)
                    .build()
            )
            addRenderableWidget(
                Button.builder(Component.literal("Leaderboard")) {
                    minecraft.setScreenAndShow(LeaderboardScreen(this))
                }.bounds(centerX - 100, cardBottom + 40, 98, 20).build()
            )
            addRenderableWidget(
                Button.builder(Component.literal("Match History")) {
                    minecraft.setScreenAndShow(MatchHistoryScreen(this))
                }.bounds(centerX + 2, cardBottom + 40, 98, 20).build()
            )

            val lastMatch = RankedState.lastMatch
            if (lastMatch != null && !RankedState.lastMatchReported) {
                addRenderableWidget(
                    Button.builder(Component.literal("⚑ Report ${lastMatch.opponent.name}")) {
                        reportLastMatch(lastMatch)
                    }.bounds(centerX - 100, cardBottom + 64, 200, 20).build()
                )
            }
        }

        addRenderableWidget(
            Button.builder(Component.literal("Back")) { onClose() }
                .bounds(centerX - 100, height - 28, 200, 20)
                .build()
        )
    }

    private fun queueLabel(): Component = Component.literal(
        if (RankedState.isQueued) "✖ Leave Queue" else "▶ Play Ranked Lockout 1v1"
    )

    private fun login() {
        val minecraft = this.minecraft
        RankedState.statusMessage = "Signing in…"

        val user = minecraft.user
        val sessionService = minecraft.services().sessionService()
        val backend = BackendClient(YabRankedClient.backendUrl, YabRankedClient.modVersion)

        YabRankedClient.workers.execute {
            val result = backend.authenticate(user.name) { serverId ->
                sessionService.joinServer(user.profileId, user.accessToken, serverId)
            }
            minecraft.execute {
                when (result) {
                    is BackendClient.AuthResult.Ok -> {
                        RankedState.backend = backend
                        RankedState.profile = result.session.profile
                        RankedState.statusMessage = null
                    }
                    is BackendClient.AuthResult.Outdated ->
                        RankedState.statusMessage = "§c${result.message}"
                    is BackendClient.AuthResult.Failed ->
                        RankedState.statusMessage = "§c${result.message}"
                }
                refresh()
            }
        }
    }

    private fun reportLastMatch(match: WireQueueServerMessage.MatchFound) {
        val minecraft = this.minecraft
        val backend = RankedState.backend ?: return
        RankedState.statusMessage = "Submitting report…"
        YabRankedClient.workers.execute {
            val status = backend.submitReport(match.matchId, "reported via post-match button")
            minecraft.execute {
                RankedState.statusMessage = status
                RankedState.lastMatchReported = true
                refresh()
            }
        }
    }

    private fun toggleQueue() {
        val minecraft = this.minecraft
        val backend = RankedState.backend ?: return

        val existing = RankedState.queue
        if (existing != null) {
            existing.leave()
            RankedState.queue = null
            RankedState.queueSnapshot = null
            RankedState.queueStatus = null
            queueButton?.message = queueLabel()
            return
        }

        RankedState.lastRatingChange = null
        RankedState.queueStatus = "Joining queue…"

        YabRankedClient.workers.execute {
            val socket = backend.joinQueue(
                format = "lockout_1v1",
                onMessage = { message -> minecraft.execute { onQueueMessage(message) } },
                onClosed = { reason ->
                    minecraft.execute {
                        RankedState.queue = null
                        RankedState.queueSnapshot = null
                        if (RankedState.activeMatch == null) {
                            RankedState.queueStatus = reason?.let { "§7Queue closed: $it" }
                        }
                        queueButton?.message = queueLabel()
                    }
                },
            )
            minecraft.execute {
                RankedState.queue = socket
                if (socket == null) RankedState.queueStatus = "§cCould not join the queue"
                queueButton?.message = queueLabel()
            }
        }
    }

    private fun onQueueMessage(message: WireQueueServerMessage) {
        val minecraft = Minecraft.getInstance()
        when (message) {
            is WireQueueServerMessage.QueueState -> {
                RankedState.queueSnapshot = message
                RankedState.queueStatus = null
            }

            is WireQueueServerMessage.QueueError -> {
                RankedState.queueSnapshot = null
                RankedState.queueStatus = "§c${message.message}"
            }

            is WireQueueServerMessage.MatchFound -> {
                RankedState.activeMatch = message
                RankedState.queue = null
                RankedState.queueSnapshot = null
                RankedState.queueStatus = null

                // reveal the opponent before connecting rather than yanking the
                // player straight into a loading screen
                minecraft.setScreenAndShow(MatchFoundScreen(parent, message))
            }
        }
    }

    // --- rendering ---

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        g.centeredText(font, "§lYAB RANKED", centerX, 18, Ui.ACCENT)
        g.centeredText(font, "Lockout 1v1", centerX, 30, Ui.TEXT_FAINT)

        val profile = RankedState.profile
        if (profile == null) {
            drawSignedOutCard(g, centerX)
        } else {
            drawProfileCard(g, centerX, profile)
        }

        drawStatusLine(g, centerX)
    }

    private fun drawSignedOutCard(g: GuiGraphicsExtractor, centerX: Int) {
        val left = centerX - CARD_WIDTH / 2
        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)
        g.centeredText(font, "Not signed in", centerX, CARD_TOP + 18, Ui.WHITE)
        g.centeredText(
            font,
            "Sign in to queue for ranked matches.",
            centerX, CARD_TOP + 32, Ui.TEXT_DIM,
        )
    }

    private fun drawProfileCard(g: GuiGraphicsExtractor, centerX: Int, profile: WireProfile) {
        val left = centerX - CARD_WIDTH / 2
        val right = left + CARD_WIDTH
        val tierColor = Ui.tierColor(profile.tier)

        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)
        Ui.accentBar(g, left, CARD_TOP, CARD_HEIGHT, tierColor)

        val padLeft = left + 10
        val padRight = right - 10

        // avatar, then name + season
        PlayerHeads.draw(g, padLeft, CARD_TOP + 8, 24, profile.uuid, profile.name, tierColor)
        g.text(font, profile.name, padLeft + 30, CARD_TOP + 8, Ui.WHITE)
        Ui.textRight(g, font, "Season ${profile.season}", padRight, CARD_TOP + 8, Ui.TEXT_FAINT)

        // tier badge + rating, the headline pair
        Ui.rankBadge(g, padLeft + 30, CARD_TOP + 19, profile.tier)
        g.text(font, profile.tier, padLeft + 52, CARD_TOP + 22, tierColor)
        val ratingText = "${profile.rating}"
        Ui.textRight(g, font, ratingText, padRight, CARD_TOP + 22, Ui.WHITE)
        Ui.textRight(g, font, "MMR", padRight - font.width(ratingText) - 4, CARD_TOP + 22, Ui.TEXT_FAINT)

        // placements replace the rank line until the player is placed
        val subtitle = if (profile.placementMatchesRemaining > 0) {
            "§e${profile.placementMatchesRemaining}§7 placement matches remaining"
        } else {
            profile.rank?.let { "§7Rank §f#$it" } ?: "§7Unranked this season"
        }
        g.text(font, subtitle, padLeft + 30, CARD_TOP + 36, Ui.TEXT_DIM)

        Ui.divider(g, padLeft, CARD_TOP + 50, CARD_WIDTH - 20)

        // record + win rate
        val record = "§a${profile.wins}W §7/ §c${profile.losses}L" +
            if (profile.draws > 0) " §7/ §8${profile.draws}D" else ""
        g.text(font, record, padLeft, CARD_TOP + 58, Ui.WHITE)
        if (profile.wins + profile.losses > 0) {
            Ui.textRight(g, font, "${Ui.winRatePercent(profile)}% win rate", padRight, CARD_TOP + 58, Ui.TEXT_DIM)
        }
        Ui.winRateBar(g, padLeft, CARD_TOP + 70, CARD_WIDTH - 20, profile.wins, profile.losses, profile.draws)

        RankedState.lastRatingChange?.let { delta ->
            val text = if (delta >= 0) "▲ +$delta MMR last match" else "▼ $delta MMR last match"
            g.centeredText(font, text, centerX, CARD_TOP + 80, if (delta >= 0) Ui.WIN else Ui.LOSS)
        }
    }

    private fun drawStatusLine(g: GuiGraphicsExtractor, centerX: Int) {
        val y = CARD_TOP + CARD_HEIGHT + 96

        val snapshot = RankedState.queueSnapshot
        if (snapshot != null) {
            // searching indicator: dots cycle so the screen never looks frozen
            val dots = ".".repeat(((System.currentTimeMillis() / 500) % 4).toInt())
            g.centeredText(font, "Searching for an opponent$dots", centerX, y, Ui.ACCENT)
            g.centeredText(
                font,
                "§7${Ui.duration(snapshot.waitedSeconds)} elapsed · ${snapshot.playersInQueue} in queue",
                centerX, y + 12, Ui.TEXT_DIM,
            )
            return
        }

        (RankedState.queueStatus ?: RankedState.statusMessage)?.let { status ->
            g.centeredText(font, status, centerX, y, Ui.WHITE)
        }
    }

    override fun onClose() {
        // leaving the screen does not leave the queue; the socket keeps running
        // and auto-connects when a match is found
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val CARD_WIDTH = 220
        const val CARD_HEIGHT = 92
        const val CARD_TOP = 46
    }
}
