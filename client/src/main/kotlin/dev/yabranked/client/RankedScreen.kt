package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class RankedScreen(
    private val parent: Screen?,
) : Screen(Component.literal("YAB Ranked")) {

    private var queueButton: RankedButton? = null
    private var pickerButton: RankedButton? = null

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    /** Where the status/searching line is drawn; set during [init] so it always
     *  sits in the reserved strip under the card and never lands on a button. */
    private var statusY = CARD_TOP + CARD_HEIGHT + STATUS_GAP

    /** Rebuilds the widgets in place; keeps the screen instance and its state. */
    private fun refresh() = rebuildWidgets()

    /** Fingerprint of the state that drives the button layout. When it changes
     *  (e.g. the queue socket connects on a worker thread, a match is found, or
     *  the queue closes), the widgets are rebuilt so labels/enabled-state track
     *  it. Without this, joining the queue left the button reading "Play" because
     *  [RankedQueue.join] sets the socket asynchronously, after refresh() ran. */
    private var lastSignature = Int.MIN_VALUE

    private fun stateSignature(): Int {
        var h = if (RankedState.isAuthenticated) 1 else 0
        h = h * 31 + (if (RankedState.isQueued) 1 else 0)
        h = h * 31 + (if (RankedState.activeMatch != null) 1 else 0)
        h = h * 31 + RankedState.selectedFormat.name.hashCode()
        return h
    }

    override fun init() {
        RankedState.onRankedScreen = true
        lastSignature = stateSignature()
        val centerX = width / 2
        val cardBottom = CARD_TOP + CARD_HEIGHT

        // Reserve a two-line strip directly under the card for the status /
        // searching text, then flow the buttons below it. Deriving every Y from
        // this cursor keeps the layout from colliding at any GUI scale.
        statusY = cardBottom + STATUS_GAP
        var by = statusY + STATUS_STRIP

        // while a match is live the menu's job is the match, not the queue
        val liveMatch = RankedState.activeMatch
        if (liveMatch != null) {
            addRenderableWidget(
                RankedButton(centerX - 100, by, 200, 20, Component.literal("§cForfeit match")) {
                    minecraft.setScreenAndShow(ForfeitConfirmScreen(this, liveMatch.opponent.name))
                }
            )
            by += ROW
            addBackButton(centerX, by)
            return
        }

        if (!RankedState.isAuthenticated) {
            addRenderableWidget(
                RankedButton(centerX - 100, by, 200, 20, Component.literal("Log in with Minecraft")) { login() }
            )
            by += ROW
        } else {
            // the format is locked in once queued, so the picker is disabled
            // rather than removed to keep the button layout from jumping
            pickerButton = RankedButton(centerX - 100, by, 200, 20, formatLabel()) { cycleFormat() }
            pickerButton!!.active = !RankedState.isQueued
            addRenderableWidget(pickerButton!!)
            by += ROW

            queueButton = addRenderableWidget(
                RankedButton(centerX - 100, by, 200, 20, queueLabel(), Ui.ICON_PLAY) { toggleQueue() }
            )
            // Enable only when the corresponding action is valid
            queueButton?.active = if (RankedState.isQueued) true else RankedState.isAuthenticated
            by += ROW

            addRenderableWidget(
                RankedButton(centerX - 100, by, 98, 20, Component.literal("Leaderboard"), Ui.ICON_LEADERBOARD) {
                    minecraft.setScreenAndShow(LeaderboardScreen(this))
                }
            )
            addRenderableWidget(
                RankedButton(centerX + 2, by, 98, 20, Component.literal("History (H)"), Ui.ICON_HISTORY) {
                    minecraft.setScreenAndShow(MatchHistoryScreen(this))
                }
            )
            by += ROW
        }

        addRenderableWidget(
            RankedButton(centerX - 100, by, 200, 20, Component.literal("Options"), Ui.ICON_OPTIONS) {
                minecraft.setScreenAndShow(RankedOptionsScreen(this))
            }
        )
        by += ROW

        addBackButton(centerX, by)
    }

    /** Rebuild when the queue/auth/match state shifts under us, including the
     *  async socket connect that [RankedQueue.join] completes on a worker. */
    override fun tick() {
        val sig = stateSignature()
        if (sig != lastSignature) {
            lastSignature = sig
            refresh()
        }
    }

    /** Pins Back to the bottom when there is room, else places it just below the
     *  flowed content so it can never overlap a button on a short screen. */
    private fun addBackButton(centerX: Int, contentBottom: Int) {
        val y = maxOf(height - 28, contentBottom + 4)
        addRenderableWidget(
            RankedButton(centerX - 100, y, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
    }

    private fun queueLabel(): Component = Component.literal(
        if (RankedState.isQueued) {
            "Leave Queue (L)"
        } else {
            val format = RankedState.selectedFormat
            val verb = if (format.ranked) "Play Ranked" else "Play Casual"
            "$verb ${format.displayName} (J)"
        }
    )

    private fun formatLabel(): Component = Component.literal(
        "Format: ${RankedState.selectedFormat.displayName}" +
            if (RankedState.selectedFormat.ranked) " (rated)" else " (casual)"
    )

    /** Steps through the available formats; wraps back to the first. */
    private fun cycleFormat() {
        val formats = MatchFormat.entries
        val next = (formats.indexOf(RankedState.selectedFormat) + 1) % formats.size
        RankedState.selectedFormat = formats[next]
        refresh()
    }

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
                        // The server is authoritative for these two prefs; mirror
                        // them into the local toggles so the options screen shows
                        // the real state and doesn't clobber it on next save.
                        RankedState.hideOwnFlag = result.session.profile.hideFlag
                        RankedState.hideElo = result.session.profile.hideRating
                        RankedState.statusMessage = null
                        // Populate the win streak shown on the profile card.
                        val uuid = result.session.profile.uuid
                        YabRankedClient.workers.execute {
                            val hist = backend.fetchHistory(uuid, limit = 20).orElse(emptyList())
                            minecraft.execute {
                                RankedState.winStreak = RankedState.currentWinStreak(hist)
                                refresh()
                            }
                        }
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

    private fun toggleQueue() {
        if (RankedState.isQueued) RankedQueue.leave() else RankedQueue.join()
        // rebuild so the queue label and the picker's enabled state track the
        // new queue status (the socket itself connects asynchronously)
        refresh()
    }

    // --- rendering ---

    /** Backdrop draws in the background pass so the buttons stay on top of it. */
    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        Ui.header(g, centerX - 110, 10, 220, 30)
        g.centeredText(font, "§lYAB RANKED", centerX, 16, Ui.ACCENT)
        g.centeredText(font, RankedState.selectedFormat.displayName, centerX, 28, Ui.TEXT_FAINT)

        val profile = RankedState.profile
        if (profile == null) {
            if (!RankedState.isAuthenticated) drawSignedOutCard(g, centerX) else drawLoadingCard(g, centerX)
        } else {
            drawProfileCard(g, centerX, profile)
        }

        drawStatusLine(g, centerX)

        drawDisabledHints(g, mouseX, mouseY)

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    // Keyboard shortcuts are implemented via Fabric KeyMappings in YabRankedClient.

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

    private fun drawProfileCard(g: GuiGraphicsExtractor, centerX: Int, profile: PlayerProfile) {
        val left = centerX - CARD_WIDTH / 2
        val right = left + CARD_WIDTH
        val tierColor = Ui.tierColor(profile.tier)

        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)
        // Banner image behind the card (mcsr BannerCardWidget look). Inset so the
        // panel's nine-slice corners still frame it; the helper dims it for text.
        Ui.drawUserBackground(g, left + 3, CARD_TOP + 3, CARD_WIDTH - 6, CARD_HEIGHT - 6, profile.background)
        Ui.accentBar(g, left, CARD_TOP, CARD_HEIGHT, tierColor)

        val padLeft = left + 10
        val padRight = right - 10

        // Avatar on the left, crest on the right, text in the column between —
        // the crest previously sat inline and collided with the line below it.
        Ui.slot(g, padLeft - 2, CARD_TOP + 8, 28)
        PlayerHeads.draw(g, padLeft, CARD_TOP + 10, 24, profile.uuid, profile.name, tierColor)

        Ui.slot(g, padRight - 30, CARD_TOP + 8, 30)
        Ui.rankBadge(g, padRight - 27, CARD_TOP + 11, profile.tier, size = 24)

        val textLeft = padLeft + 32
        var nameX = textLeft
        if (RankedState.showFlags && !RankedState.hideOwnFlag) {
            profile.country?.let {
                Ui.flagIcon(g, nameX, CARD_TOP + 9, it, 9)
                nameX += 12
            }
        }
        // Header column: name / tier / rating on a 12px grid, one colour each.
        // No mid-string colour switches — the crest and accent bar already carry
        // the tier hue, so the text stays greyscale.
        g.text(font, profile.name, nameX, CARD_TOP + 8, Ui.WHITE)
        g.text(font, profile.tier, textLeft, CARD_TOP + 20, tierColor)

        val ratingLine = if (RankedState.hideElo) "MMR hidden"
            else profile.rank?.let { "${profile.rating} MMR · Rank #$it" } ?: "${profile.rating} MMR"
        g.text(font, ratingLine, textLeft, CARD_TOP + 32, Ui.TEXT_DIM)

        // One quiet secondary line — placements, else streak, else season.
        val secondary = when {
            profile.placementMatchesRemaining > 0 ->
                "${profile.placementMatchesRemaining} placement matches left"
            RankedState.winStreak >= 2 -> "${RankedState.winStreak} win streak"
            else -> "Season ${profile.season}"
        }
        g.text(font, secondary, textLeft, CARD_TOP + 44, Ui.TEXT_FAINT)

        // Record in neutral text — the colour lives in the win-rate bar below it,
        // not smeared across the numbers.
        val record = "${profile.wins}W · ${profile.losses}L" +
            if (profile.draws > 0) " · ${profile.draws}D" else ""
        g.text(font, record, padLeft, CARD_TOP + 62, Ui.TEXT_DIM)
        if (profile.wins + profile.losses > 0) {
            Ui.textRight(g, font, "${Ui.winRatePercent(profile)}%", padRight, CARD_TOP + 62, Ui.TEXT_FAINT)
        }
        Ui.winRateBar(g, padLeft, CARD_TOP + 74, CARD_WIDTH - 20, profile.wins, profile.losses, profile.draws)
    }

    private fun drawLoadingCard(g: GuiGraphicsExtractor, centerX: Int) {
        val left = centerX - CARD_WIDTH / 2
        val right = left + CARD_WIDTH
        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)

        val padLeft = left + 10
        val padRight = right - 10
        // placeholders: avatar and crest slots
        Ui.slot(g, padLeft - 2, CARD_TOP + 8, 28)
        Ui.slot(g, padRight - 30, CARD_TOP + 8, 30)

        val sk = 0xFF2A2A2A.toInt()
        val y0 = CARD_TOP + 8
        // header column skeleton, matching the real card's 12px grid
        g.fill(padLeft + 32, y0, padRight - 40, y0 + 6, sk)        // name
        g.fill(padLeft + 32, y0 + 12, padRight - 60, y0 + 18, sk)  // tier
        g.fill(padLeft + 32, y0 + 24, padRight - 50, y0 + 30, sk)  // rating
        g.fill(padLeft + 32, y0 + 36, padRight - 120, y0 + 42, sk) // secondary
        // record + bar placeholders
        g.fill(padLeft, CARD_TOP + 62, padLeft + 80, CARD_TOP + 68, sk)
        g.fill(padLeft, CARD_TOP + 74, padRight, CARD_TOP + 77, 0xFF1C1C1C.toInt())

        g.centeredText(font, "Loading…", centerX, CARD_TOP + CARD_HEIGHT + 12, Ui.TEXT_DIM)
    }

    private fun drawStatusLine(g: GuiGraphicsExtractor, centerX: Int) {
        val y = statusY

        val snapshot = RankedState.queueSnapshot
        if (snapshot != null) {
            // searching indicator: dots cycle so the screen never looks frozen
            val dots = ".".repeat(((System.currentTimeMillis() / 500) % 4).toInt())
            if (snapshot.preparingMatch) {
                // Paired already — the wait now is the match server booting,
                // which is a very different thing to still be looking at.
                g.centeredText(font, "Opponent found — starting server$dots", centerX, y, Ui.ACCENT)
                g.centeredText(
                    font,
                    "§7${Ui.duration(snapshot.waitedSeconds)} · this can take up to a minute",
                    centerX, y + 12, Ui.TEXT_DIM,
                )
                return
            }
            g.centeredText(font, "Searching for an opponent$dots", centerX, y, Ui.ACCENT)
            // Rough ETA: with someone else already queued a match is imminent;
            // otherwise the MMR band widens with wait time, so the estimate
            // counts down as you wait.
            val eta = if (snapshot.playersInQueue >= 2) "~any moment"
                else "~${maxOf(5L, 45L - snapshot.waitedSeconds)}s"
            g.centeredText(
                font,
                "§7${Ui.duration(snapshot.waitedSeconds)} elapsed · ${snapshot.playersInQueue} in queue · est. $eta",
                centerX, y + 12, Ui.TEXT_DIM,
            )
            return
        }

        (RankedState.queueStatus ?: RankedState.statusMessage)?.let { status ->
            g.centeredText(font, status, centerX, y, Ui.WHITE)
        }
    }

    private fun drawDisabledHints(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        fun isHovering(btn: RankedButton?): Boolean {
            if (btn == null) return false
            val bx = btn.x
            val by = btn.y
            val bw = btn.width
            val bh = btn.height
            return mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh
        }

        // Hint shown as a cursor tooltip, not stamped under the button — the
        // buttons stack only 4px apart, so text below one landed on the next.
        val hint = when {
            pickerButton?.active == false && isHovering(pickerButton) -> "Format locked while queued"
            queueButton?.active == false && isHovering(queueButton) -> "Please sign in first"
            else -> null
        }
        if (hint != null) drawTooltip(g, mouseX, mouseY, hint)
    }

    /** Small boxed tooltip near the cursor, clamped to the screen. Drawn in the
     *  content pass so it sits above the buttons. */
    private fun drawTooltip(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, text: String) {
        val w = font.width(text)
        val x = (mouseX + 10).coerceAtMost(width - w - 6)
        val y = (mouseY - 14).coerceAtLeast(2)
        Ui.panel(g, x - 3, y - 3, w + 6, 14)
        g.text(font, text, x, y, Ui.WHITE)
    }

    override fun onClose() {
        // leaving the screen does not leave the queue; the socket keeps running
        // and auto-connects when a match is found
        RankedState.onRankedScreen = false
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val CARD_WIDTH = 220
        const val CARD_HEIGHT = 84
        const val CARD_TOP = 46

        /** Vertical rhythm for the button column: 20px button + 4px gap. */
        const val ROW = 24
        /** Space between the card and the status line. */
        const val STATUS_GAP = 6
        /** Height reserved for the (up to two-line) status strip under the card. */
        const val STATUS_STRIP = 26
    }
}
