package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.gui.components.toasts.ToastManager

/**
 * Non-blocking ranked notification (match found, tier change, report filed).
 * Drawn in the same flat-panel style as the ranked screens.
 */
class RankedToast(
    private val title: String,
    private val message: String,
    private val accent: Int = Ui.ACCENT,
) : Toast {

    private var visibility = Toast.Visibility.SHOW
    private var firstUpdate = -1L

    override fun getWantedVisibility(): Toast.Visibility = visibility

    override fun update(manager: ToastManager, time: Long) {
        if (firstUpdate < 0) firstUpdate = time
        if (time - firstUpdate >= DURATION_MS) visibility = Toast.Visibility.HIDE
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, font: Font, time: Long) {
        val w = width()
        val h = height()
        Ui.panel(g, 0, 0, w, h)
        Ui.accentBar(g, 0, 0, h, accent)

        g.text(font, title, 10, 7, accent)
        g.text(font, message, 10, 19, Ui.WHITE)
    }

    companion object {
        private const val DURATION_MS = 5000L

        fun show(title: String, message: String, accent: Int = Ui.ACCENT) {
            Minecraft.getInstance().gui.toastManager().addToast(RankedToast(title, message, accent))
        }
    }
}
