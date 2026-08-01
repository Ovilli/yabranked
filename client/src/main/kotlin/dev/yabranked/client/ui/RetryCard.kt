package dev.yabranked.client.ui

import dev.yabranked.client.Sfx
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The message card a screen shows instead of its content, with a way out of a
 * failed read.
 *
 * A backend read that fails leaves the screen holding a sentence and nothing
 * else. Every list screen had exactly that: a card saying "Could not reach the
 * server", a Back button, and no way to ask again — the only way to retry was to
 * leave the screen and come back, which is a thing a player has to guess. The
 * request that failed is usually a blip and the second attempt usually works,
 * which is what makes the missing button expensive rather than merely untidy.
 *
 * Drawn rather than made of widgets because the state it belongs to is decided
 * during the draw: a screen learns its read failed on a worker thread, and
 * rebuilding the widget tree from there means every one of these screens has to
 * remember to. A card that draws its own button cannot forget.
 *
 * Only [Loadable.Failed] gets a button. A read still in flight is not something
 * to ask again — the answer is on its way.
 */
class RetryCard(private val onRetry: () -> Unit) {

    /** The button's rectangle from the last frame, or null when none was drawn. */
    private var box: IntArray? = null

    /**
     * Draw [message] in a card, with a Retry button when [retryable].
     *
     * Returns the height drawn, so a caller can flow content beneath it.
     */
    fun draw(
        g: GuiGraphicsExtractor,
        font: Font,
        centerX: Int,
        top: Int,
        message: String,
        retryable: Boolean,
        mouseX: Int = -1,
        mouseY: Int = -1,
        width: Int = 220,
    ): Int {
        if (!retryable) {
            box = null
            Ui.messageCard(g, font, centerX, top, message, width = width)
            return CARD_HEIGHT
        }

        val height = CARD_HEIGHT + BUTTON_HEIGHT + 6
        Ui.messageCard(g, font, centerX, top, message, width = width, height = height)

        val bw = 72
        val bx = centerX - bw / 2
        val by = top + height - BUTTON_HEIGHT - 7
        val over = mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + BUTTON_HEIGHT
        box = intArrayOf(bx, by, bx + bw, by + BUTTON_HEIGHT)

        g.fill(bx, by, bx + bw, by + BUTTON_HEIGHT, Ui.PANEL_BORDER)
        g.fill(bx + 1, by + 1, bx + bw - 1, by + BUTTON_HEIGHT - 1, if (over) Ui.HOVER else Ui.PANEL_BG)
        g.centeredText(font, "Retry", centerX + 4, by + 5, if (over) Ui.WHITE else Ui.TEXT_DIM)
        Ui.icon(g, Ui.ICON_REFRESH, bx + 8, by + 4, 9, if (over) Ui.WHITE else Ui.TEXT_SOFT)
        return height
    }

    /** True when the click was on the button, in which case the retry has run. */
    fun clicked(mouseX: Double, mouseY: Double): Boolean {
        val b = box ?: return false
        if (mouseX < b[0] || mouseX >= b[2] || mouseY < b[1] || mouseY >= b[3]) return false
        Sfx.select()
        onRetry()
        return true
    }

    private companion object {
        const val CARD_HEIGHT = 38
        const val BUTTON_HEIGHT = 18
    }
}
