package dev.yabranked.client

import dev.yabranked.client.ui.Ui

/**
 * Ranked notifications (match found, tier change, report filed).
 *
 * This used to be a vanilla [net.minecraft.client.gui.components.toasts.Toast]
 * drawn by the game's own toast manager, which put it in the same top-right
 * corner as [RankedNotice] with neither aware of the other: two notifications
 * that happened to be raised through different calls drew on top of each other.
 * The class stays as the name every caller already uses, but it is now only a
 * way into the one stack, so everything queues in one column.
 *
 * `durationMs` is accepted and ignored: the stack holds every notice for the
 * same time, which is what makes the column read as a list rather than as a set
 * of independently expiring boxes.
 */
object RankedToast {

    fun show(
        title: String,
        message: String,
        accent: Int = Ui.ACCENT,
        durationMs: Long = 0,
        narrate: Boolean = true,
    ) {
        RankedNotice.show(title, message, accent, narrate)
    }

    fun showInfo(title: String, message: String, durationMs: Long = 0) =
        show(title, message, Ui.ACCENT)

    fun showError(title: String, message: String, durationMs: Long = 0) =
        show(title, message, Ui.LOSS)
}
