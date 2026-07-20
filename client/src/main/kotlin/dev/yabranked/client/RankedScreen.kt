package dev.yabranked.client

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component

private const val WHITE = -1

class RankedScreen(
    private val parent: Screen,
) : Screen(Component.literal("YAB Ranked")) {

    private var queueButton: Button? = null

    private fun refresh() {
        minecraft.setScreenAndShow(RankedScreen(parent))
    }

    override fun init() {
        val centerX = width / 2

        if (!RankedState.isAuthenticated) {
            addRenderableWidget(
                Button.builder(Component.literal("Log in to Ranked")) { login() }
                    .bounds(centerX - 100, height / 2 - 12, 200, 20)
                    .build()
            )
        } else {
            queueButton = addRenderableWidget(
                Button.builder(queueLabel()) { toggleQueue() }
                    .bounds(centerX - 100, height / 2 - 12, 200, 20)
                    .build()
            )
            addRenderableWidget(
                Button.builder(Component.literal("Leaderboard")) {
                    minecraft.setScreenAndShow(LeaderboardScreen(this))
                }.bounds(centerX - 100, height / 2 + 14, 98, 20).build()
            )
            addRenderableWidget(
                Button.builder(Component.literal("Match History")) {
                    minecraft.setScreenAndShow(MatchHistoryScreen(this))
                }.bounds(centerX + 2, height / 2 + 14, 98, 20).build()
            )

            val lastMatch = RankedState.lastMatch
            if (lastMatch != null && !RankedState.lastMatchReported) {
                addRenderableWidget(
                    Button.builder(Component.literal("Report ${lastMatch.opponent.name}")) {
                        reportLastMatch(lastMatch)
                    }.bounds(centerX - 100, height / 2 + 40, 200, 20).build()
                )
            }
        }

        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(centerX - 100, height - 28, 200, 20)
                .build()
        )
    }

    private fun queueLabel(): Component =
        Component.literal(if (RankedState.isQueued) "Leave Queue" else "Queue: Ranked Lockout 1v1")

    private fun login() {
        val minecraft = this.minecraft
        RankedState.statusMessage = "Logging in..."

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
        RankedState.statusMessage = "Submitting report..."
        YabRankedClient.workers.execute {
            // free-text reasons come later; a generic reason keeps the UI to one click
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
            RankedState.queueStatus = null
            queueButton?.message = queueLabel()
            return
        }

        RankedState.lastRatingChange = null
        RankedState.queueStatus = "Joining queue..."

        YabRankedClient.workers.execute {
            val socket = backend.joinQueue(
                format = "lockout_1v1",
                onMessage = { message -> minecraft.execute { onQueueMessage(message) } },
                onClosed = { reason ->
                    minecraft.execute {
                        RankedState.queue = null
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
                RankedState.queueStatus =
                    "In queue (${message.playersInQueue} waiting) — ${message.waitedSeconds}s"
            }

            is WireQueueServerMessage.QueueError -> {
                RankedState.queueStatus = "§c${message.message}"
            }

            is WireQueueServerMessage.MatchFound -> {
                RankedState.activeMatch = message
                RankedState.queue = null
                RankedState.queueStatus = null

                val address = ServerAddress.parseString(message.serverAddress)
                val serverData = ServerData(
                    "YAB Ranked vs ${message.opponent.name}",
                    message.serverAddress,
                    ServerData.Type.OTHER,
                )
                ConnectScreen.startConnecting(this, minecraft, address, serverData, false, null)
            }
        }
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val centerX = width / 2
        extractor.centeredText(font, title, centerX, 20, WHITE)

        val profile = RankedState.profile
        if (profile != null) {
            val placements = if (profile.placementMatchesRemaining > 0) {
                " §7(${profile.placementMatchesRemaining} placement matches left)"
            } else ""
            val rank = profile.rank?.let { " §7#$it" } ?: ""
            extractor.centeredText(
                font,
                "§e${profile.name}§r — ${profile.tier} · ${profile.rating} MMR$rank$placements §8(Season ${profile.season})",
                centerX, 40, WHITE,
            )
            extractor.centeredText(
                font,
                "§a${profile.wins}W §c${profile.losses}L §7${profile.draws}D",
                centerX, 52, WHITE,
            )
        }

        RankedState.lastRatingChange?.let { delta ->
            val text = if (delta >= 0) "§aLast match: +$delta MMR" else "§cLast match: $delta MMR"
            extractor.centeredText(font, text, centerX, 66, WHITE)
        }

        (RankedState.queueStatus ?: RankedState.statusMessage)?.let { status ->
            extractor.centeredText(font, status, centerX, height / 2 - 30, WHITE)
        }
    }

    override fun onClose() {
        // leaving the screen does not leave the queue; players can browse
        // while waiting. The socket keeps running and reconnects the flow
        // through RankedState when the match is found.
        minecraft.setScreenAndShow(parent)
    }
}
