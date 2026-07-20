package dev.yabranked.client.ui

import dev.yabranked.client.RankedState
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Compact "still searching" badge, drawn wherever the player happens to be so
 * that queueing does not require sitting in the ranked menu.
 */
object QueueBadge {

    /** True when the player is queued and not already in a match. */
    fun isVisible(): Boolean = RankedState.isQueued && RankedState.activeMatch == null

    fun width(font: Font): Int = maxOf(font.width(LABEL), font.width(sampleStatus())) + 20

    private fun sampleStatus(): String = "00:00 · 00 in queue"

    private fun status(): String {
        val snapshot = RankedState.queueSnapshot ?: return "Connecting…"
        return "${Ui.duration(snapshot.waitedSeconds)} · ${snapshot.playersInQueue} in queue"
    }

    fun draw(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val w = width(font)
        Ui.panel(g, x, y, w, HEIGHT)
        Ui.accentBar(g, x, y, HEIGHT, Ui.ACCENT)

        // cycling dots so a quiet queue never looks frozen
        val dots = ".".repeat(((System.currentTimeMillis() / 500) % 4).toInt())
        g.text(font, "$LABEL$dots", x + 8, y + 6, Ui.ACCENT)
        g.text(font, status(), x + 8, y + 18, Ui.TEXT_DIM)
    }

    const val HEIGHT = 30
    private const val LABEL = "§lSEARCHING"
}
