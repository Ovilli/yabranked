package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * Shown after leaving a ranked match: the outcome, the rating movement, and a
 * tier promotion if one happened. Previously the result only surfaced if the
 * player happened to reopen the ranked menu.
 */
class MatchResultScreen(
    private val entry: WireHistoryEntry,
    private val profileBefore: WireProfile?,
    private val profileAfter: WireProfile,
) : Screen(Component.literal("Match Result")) {

    private val promoted =
        profileBefore != null && profileBefore.tier != profileAfter.tier && profileAfter.rank != null

    /** Ticks since open, used to count the rating up rather than snapping. */
    private var ticks = 0

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    override fun init() {
        RankedState.onResultScreen = true
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 52, 200, 20, Component.literal("Queue again (R)"), Ui.ICON_PLAY) { queueAgain() }
        )
        // Quick actions row above the main button
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 76, 64, 20, Component.literal("History")) { openHistory() }
        )
        addRenderableWidget(
            RankedButton(width / 2 - 32, height - 76, 64, 20, Component.literal("Copy ID")) { copyMatchId() }
        )
        // Reporting lives here (post-match), not on the main menu: this is the
        // "player you recently played with". Hidden once the match is reported.
        addRenderableWidget(
            RankedButton(width / 2 + 36, height - 76, 64, 20, Component.literal("§cReport")) { openReport() }
        ).apply { active = !RankedState.lastMatchReported }
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Done"), Ui.ICON_BACK) { onClose() }
        )

        val sound = when {
            promoted -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
            entry.result == "win" -> SoundEvents.PLAYER_LEVELUP
            else -> SoundEvents.UI_TOAST_IN
        }
        minecraft.soundManager.play(SimpleSoundInstance.forUI(sound, 1.0f))
    }

    override fun tick() {
        ticks++
    }

    private fun queueAgain() {
        if (!RankedState.isQueued && RankedState.isAuthenticated) {
            RankedQueue.join()
        }
        val title = TitleScreen()
        minecraft.setScreenAndShow(RankedScreen(title))
    }

    private fun copyMatchId() {
        minecraft.keyboardHandler.setClipboard(entry.matchId)
        RankedToast.showInfo("Copied", "Match ID copied to clipboard")
    }

    private fun openHistory() {
        minecraft.setScreenAndShow(MatchHistoryScreen(this))
    }

    private fun openReport() {
        minecraft.setScreenAndShow(ReportScreen(this, entry.matchId, entry.opponent.name))
    }

    // Keyboard shortcut (R) is handled via a Fabric KeyMapping in YabRankedClient.

    private fun resultColor() = when (entry.result) {
        "win" -> Ui.WIN
        "loss" -> Ui.LOSS
        "draw" -> Ui.DRAW
        else -> Ui.TEXT_FAINT
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        val headline = when (entry.result) {
            "win" -> "§lVICTORY"
            "loss" -> "§lDEFEAT"
            "draw" -> "§lDRAW"
            else -> "§lMATCH VOIDED"
        }
        // Result icon left of the headline: crown on promotion, else win/loss tick.
        val headlineW = font.width(headline)
        val iconIdx = when {
            promoted -> Ui.ICON_CROWN
            entry.result == "win" -> Ui.ICON_WIN
            entry.result == "loss" -> Ui.ICON_LOSS
            else -> null
        }
        if (iconIdx != null) {
            Ui.icon(g, iconIdx, centerX - headlineW / 2 - 16, 27, 12, resultColor())
        }
        g.centeredText(font, headline, centerX, 28, resultColor())
        val ago = Ui.relativeTime(entry.completedAt)
        val headerLine = "§7vs ${entry.opponent.name} · ${Ui.duration(entry.durationSeconds)}" +
            if (ago.isNotEmpty()) " · $ago" else ""
        g.centeredText(font, headerLine, centerX, 42, Ui.TEXT_DIM)
        if (RankedState.showFlags) {
            val left = centerX - font.width(headerLine) / 2
            val prefixW = font.width("vs ")
            Ui.flagIcon(g, left + prefixW, 40, entry.opponent.country, 8)
        }

        val left = centerX - CARD_WIDTH / 2
        val top = 60
        Ui.panel(g, left, top, CARD_WIDTH, CARD_HEIGHT)
        // Banner behind the card, matching the profile card treatment.
        Ui.drawUserBackground(g, left + 3, top + 3, CARD_WIDTH - 6, CARD_HEIGHT - 6, profileAfter.background)
        Ui.accentBar(g, left, top, CARD_HEIGHT, resultColor())

        val after = entry.ratingAfter
        if (after == null) {
            g.centeredText(font, "§7No rating change", centerX, top + 26, Ui.TEXT_DIM)
        } else {
            val delta = after - entry.ratingBefore
            // count the rating up over ~1s so the change is legible
            val progress = (ticks / 20f).coerceIn(0f, 1f)
            val shown = entry.ratingBefore + (delta * progress).toInt()

            // Hide the absolute rating if the player opted out, but still show
            // the delta chip — the change carries no standings information.
            val shownText = if (RankedState.hideElo) "§7•••" else "$shown"
            g.centeredText(font, shownText, centerX, top + 18, Ui.WHITE)
            g.centeredText(font, "§7MMR", centerX, top + 30, Ui.TEXT_FAINT)
            val deltaText = if (delta >= 0) "▲ +$delta" else "▼ $delta"
            val color = if (delta >= 0) Ui.WIN else Ui.LOSS
            val bg = (0x33 shl 24) or (color and 0x00FFFFFF)
            Ui.chip(g, font, centerX, top + 40, deltaText, bg, Ui.WHITE)
        }

        Ui.rankBadge(g, left + 10, top + 20, profileAfter.tier)
        g.text(font, profileAfter.tier, left + 10, top + 40, Ui.tierColor(profileAfter.tier))
        profileAfter.rank?.let {
            Ui.textRight(g, font, "Rank #$it", left + CARD_WIDTH - 10, top + 40, Ui.TEXT_DIM)
        }

        val infoY = top + CARD_HEIGHT + 12
        when {
            promoted && profileBefore != null -> drawRankUp(g, centerX, infoY, profileBefore, profileAfter)
            profileAfter.placementMatchesRemaining > 0 -> {
                g.centeredText(
                    font,
                    "§7${profileAfter.placementMatchesRemaining} placement matches remaining",
                    centerX, infoY, Ui.TEXT_DIM,
                )
                drawStreak(g, centerX, infoY + 14)
            }
            else -> drawStreak(g, centerX, infoY)
        }

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    /** Win streak, on a win only. Fed from history by the disconnect poll. */
    private fun drawStreak(g: GuiGraphicsExtractor, centerX: Int, y: Int) {
        if (entry.result == "win" && RankedState.winStreak >= 2) {
            g.centeredText(font, "§6▲ ${RankedState.winStreak} win streak", centerX, y, Ui.WIN)
        }
    }

    /**
     * Promotion flourish: the new crest eases up to full size with a soft glow,
     * then the tier transition reads underneath. Driven by [ticks] so it plays
     * once when the screen opens.
     */
    private fun drawRankUp(g: GuiGraphicsExtractor, centerX: Int, y: Int, before: WireProfile, after: WireProfile) {
        val prog = (ticks / 10f).coerceIn(0f, 1f)
        val ease = 1f - (1f - prog) * (1f - prog) // easeOutQuad
        val size = (10 + 22 * ease).toInt()
        val crestX = centerX - size / 2

        Ui.softGlow(g, centerX, y + 2, size + 16, size, Ui.ACCENT)
        Ui.rankBadge(g, crestX, y, after.tier, size)
        g.centeredText(font, "§lTIER UP!", centerX, y + size + 4, Ui.ACCENT)
        g.centeredText(font, "${before.tier} → ${after.tier}", centerX, y + size + 16, Ui.TEXT_DIM)
    }

    override fun onClose() {
        RankedState.onResultScreen = false
        minecraft.setScreenAndShow(TitleScreen())
    }

    // Keyboard shortcut (R) hint is shown in the label; to wire actual
    // key handling, override keyPressed(KeyEvent) according to the MC API
    // version you target.

    private companion object {
        const val CARD_WIDTH = 200
        const val CARD_HEIGHT = 62
    }
}
