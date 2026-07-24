package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.QueueBadge
import dev.yabranked.client.ui.Ui
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * In-match overlay. Ranked context used to disappear the moment you connected —
 * this keeps the opponent, the stakes, and the elapsed time on screen for the
 * whole match without competing with YAB's own bingo HUD (top-left corner,
 * which YAB leaves free).
 */
class RankedHud : HudElement {

    override fun extractRenderState(g: GuiGraphicsExtractor, tracker: DeltaTracker) {
        val minecraft = Minecraft.getInstance()
        val font = minecraft.font

        val match = RankedState.activeMatch
        if (match == null) {
            // queued while playing elsewhere: show the search badge instead
            if (QueueBadge.isVisible()) QueueBadge.draw(g, font, 4, 4)
            return
        }

        val elapsed = RankedState.matchStartedAt?.let {
            Ui.duration((System.currentTimeMillis() - it) / 1000)
        } ?: "—"

        val opponent = "vs ${match.opponent.name}"
        val rating = when {
            RankedState.hideOpponentElo -> match.opponentTier
            match.opponentRating > 0 -> "${match.opponentRating} MMR"
            else -> match.opponentTier
        }
        val width = maxOf(font.width(opponent), font.width(rating)) + 44

        val x = 4
        val y = 4

        Ui.panel(g, x, y, width, HEIGHT)
        Ui.accentBar(g, x, y, HEIGHT, Ui.tierColor(match.opponentTier))

        g.text(font, LABEL, x + 8, y + 6, Ui.ACCENT)
        Ui.textRight(g, font, elapsed, x + width - 8, y + 6, Ui.TEXT_DIM)
        Ui.slot(g, x + 6, y + 17, 20)
        PlayerHeads.draw(g, x + 8, y + 19, 16, match.opponent.uuid, match.opponent.name, Ui.tierColor(match.opponentTier))
        g.text(font, opponent, x + 28, y + 19, Ui.WHITE)
        g.text(font, rating, x + 28, y + 30, Ui.tierColor(match.opponentTier))
    }

    private companion object {
        const val LABEL = "§lRANKED"
        const val HEIGHT = 44
    }
}
