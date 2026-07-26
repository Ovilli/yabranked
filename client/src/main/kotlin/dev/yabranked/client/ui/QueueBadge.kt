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

    // Sized for the widest text either state can show, so the badge does not
    // resize when the queue turns into a match, and nothing overflows it.
    fun width(font: Font): Int =
        maxOf(font.width(LABEL), font.width(MATCHED_LABEL), font.width(sampleStatus())) + 20

    private fun sampleStatus(): String = "00:00 · starting server"

    private fun status(): String {
        val snapshot = RankedState.queueSnapshot ?: return "Connecting…"
        // once paired, the queue count is meaningless — the wait is the server
        if (snapshot.preparingMatch) return "${Ui.duration(snapshot.waitedSeconds)} · starting server"
        return "${Ui.duration(snapshot.waitedSeconds)} · ${snapshot.playersInQueue} in queue"
    }

    fun draw(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int) {
        val w = width(font)
        Ui.panel(g, x, y, w, HEIGHT)
        Ui.accentBar(g, x, y, HEIGHT, Ui.ACCENT)

        // cycling dots so a quiet queue never looks frozen
        val dots = ".".repeat(((System.currentTimeMillis() / 500) % 4).toInt())
        val label = if (RankedState.queueSnapshot?.preparingMatch == true) MATCHED_LABEL else LABEL
        g.text(font, "$label$dots", x + 8, y + 6, Ui.ACCENT)
        g.text(font, status(), x + 8, y + 18, Ui.TEXT_DIM)
    }

    const val HEIGHT = 30
    private const val LABEL = "§lSEARCHING"
    private const val MATCHED_LABEL = "§lMATCH FOUND"
}
