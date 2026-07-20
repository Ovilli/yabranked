package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

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

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("▶ Queue again")) { queueAgain() }
                .bounds(width / 2 - 100, height - 52, 200, 20)
                .build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build()
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
        val title = TitleScreen()
        minecraft.setScreenAndShow(RankedScreen(title))
    }

    private fun resultColor() = when (entry.result) {
        "win" -> Ui.WIN
        "loss" -> Ui.LOSS
        "draw" -> Ui.DRAW
        else -> Ui.TEXT_FAINT
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
        g.centeredText(font, headline, centerX, 28, resultColor())
        g.centeredText(font, "§7vs ${entry.opponent.name} · ${Ui.duration(entry.durationSeconds)}", centerX, 42, Ui.TEXT_DIM)

        val left = centerX - CARD_WIDTH / 2
        val top = 60
        Ui.panel(g, left, top, CARD_WIDTH, CARD_HEIGHT)
        Ui.accentBar(g, left, top, CARD_HEIGHT, resultColor())

        val after = entry.ratingAfter
        if (after == null) {
            g.centeredText(font, "§7No rating change", centerX, top + 26, Ui.TEXT_DIM)
        } else {
            val delta = after - entry.ratingBefore
            // count the rating up over ~1s so the change is legible
            val progress = (ticks / 20f).coerceIn(0f, 1f)
            val shown = entry.ratingBefore + (delta * progress).toInt()

            g.centeredText(font, "$shown", centerX, top + 18, Ui.WHITE)
            g.centeredText(font, "§7MMR", centerX, top + 30, Ui.TEXT_FAINT)

            val deltaText = if (delta >= 0) "▲ +$delta" else "▼ $delta"
            g.centeredText(font, deltaText, centerX, top + 46, if (delta >= 0) Ui.WIN else Ui.LOSS)
        }

        Ui.rankBadge(g, left + 10, top + 20, profileAfter.tier)
        g.text(font, profileAfter.tier, left + 10, top + 40, Ui.tierColor(profileAfter.tier))
        profileAfter.rank?.let {
            Ui.textRight(g, font, "§7Rank §f#$it", left + CARD_WIDTH - 10, top + 40, Ui.TEXT_DIM)
        }

        if (promoted && profileBefore != null) {
            g.centeredText(
                font,
                "§6§lTIER UP! §r§7${profileBefore.tier} → §f${profileAfter.tier}",
                centerX, top + CARD_HEIGHT + 12, Ui.ACCENT,
            )
        } else if (profileAfter.placementMatchesRemaining > 0) {
            g.centeredText(
                font,
                "§7${profileAfter.placementMatchesRemaining} placement matches remaining",
                centerX, top + CARD_HEIGHT + 12, Ui.TEXT_DIM,
            )
        }
    }

    override fun onClose() {
        minecraft.setScreenAndShow(TitleScreen())
    }

    private companion object {
        const val CARD_WIDTH = 200
        const val CARD_HEIGHT = 62
    }
}
