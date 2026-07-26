package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.toasts.Toast
import net.minecraft.client.gui.components.toasts.ToastManager
import net.minecraft.network.chat.Component

/**
 * Non-blocking ranked notification (match found, tier change, report filed).
 * Drawn in the same flat-panel style as the ranked screens.
 */
class RankedToast(
    private val title: String,
    private val message: String,
    private val accent: Int = Ui.ACCENT,
    private val durationMs: Long = DEFAULT_DURATION_MS,
) : Toast {

    private var visibility = Toast.Visibility.SHOW
    private var firstUpdate = -1L

    override fun getWantedVisibility(): Toast.Visibility = visibility

    override fun update(manager: ToastManager, time: Long) {
        if (firstUpdate < 0) firstUpdate = time
        if (time - firstUpdate >= durationMs) visibility = Toast.Visibility.HIDE
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
        private const val DEFAULT_DURATION_MS = 4000L

        /**
         * @param narrate speak the toast as well as drawing it. A toast is by
         *   definition news the player did not ask for, so it is exactly the
         *   thing a narrator user misses; the opt-out exists for the cases where
         *   something louder has already said the same sentence.
         */
        fun show(
            title: String,
            message: String,
            accent: Int = Ui.ACCENT,
            durationMs: Long = DEFAULT_DURATION_MS,
            narrate: Boolean = true,
        ) {
            val minecraft = Minecraft.getInstance()
            minecraft.gui.toastManager().addToast(RankedToast(title, message, accent, durationMs))
            // No-op unless the player turned system narration on.
            if (narrate) minecraft.narrator.saySystemNow(Component.literal("$title. $message"))
        }

        fun showInfo(title: String, message: String, durationMs: Long = DEFAULT_DURATION_MS) =
            show(title, message, Ui.ACCENT, durationMs)

        fun showError(title: String, message: String, durationMs: Long = DEFAULT_DURATION_MS) =
            show(title, message, Ui.LOSS, durationMs)
    }
}
