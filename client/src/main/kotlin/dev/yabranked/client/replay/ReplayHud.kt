package dev.yabranked.client.replay

import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.ReplayEventType
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The timeline the player sees while standing in a recorded match.
 *
 * Drawn in-world rather than on a screen, because a screen would be a wall
 * between the viewer and the thing they came to look at. It is deliberately a
 * *read-out plus key hints* and not a set of buttons: an in-world overlay cannot
 * take a click without a screen behind it, and putting one there would pause the
 * player's ability to fly around exactly when they want it.
 *
 * A tick per marked moment sits on the bar — every claim, every death — so a
 * viewer can see the shape of the match before watching it, and skip between
 * moments with the perspective keys.
 */
object ReplayHud {

    fun draw(g: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int) {
        val playback = ReplayViewer.playback ?: return
        val meta = playback.meta

        val width = (screenWidth - 80).coerceIn(200, 420)
        val x = (screenWidth - width) / 2
        val y = screenHeight - HEIGHT - 8

        Ui.panel(g, x, y, width, HEIGHT)

        // Times are shown from the start of the *match*, not of the recording:
        // the handshake and the lobby are in the file but are not what anyone is
        // watching, and a clock that starts before the game does reads as broken.
        val at = ((playback.positionMillis - meta.gameStartMillis) / 1000).coerceAtLeast(0)
        val end = ((playback.endMillis - meta.gameStartMillis) / 1000).coerceAtLeast(0)
        val label = "${Ui.duration(at)} / ${Ui.duration(end)}"

        val state = when {
            playback.seeking -> "§eseeking"
            // The end is its own state, not "paused". A recording that has run out
            // and one somebody paused look identical otherwise, and the first needs
            // to say that rewinding is the only way on.
            ReplayViewer.ended -> "§6ended"
            playback.paused -> "§7paused"
            else -> "§a▶ ${trimSpeed(playback.speed)}×"
        }
        g.text(font, state, x + 8, y + 5, Ui.WHITE)
        Ui.textRight(g, font, label, x + width - 8, y + 5, Ui.TEXT_DIM)

        // --- the bar ---
        val barX = x + 8
        val barY = y + 18
        val barW = width - 16
        g.fill(barX, barY, barX + barW, barY + BAR_HEIGHT, Ui.SLOT_BG)
        val span = (playback.endMillis - meta.gameStartMillis).coerceAtLeast(1)
        val progress = ((playback.positionMillis - meta.gameStartMillis).toFloat() / span).coerceIn(0f, 1f)
        g.fill(barX, barY, barX + (barW * progress).toInt(), barY + BAR_HEIGHT, Ui.ACCENT)

        for (event in meta.events) {
            val fraction = (event.atSeconds * 1000f / span).coerceIn(0f, 1f)
            val tick = barX + (barW * fraction).toInt()
            g.fill(tick, barY - 2, tick + 1, barY + BAR_HEIGHT + 2, colorOf(event.type))
        }

        val perspective = meta.streams.firstOrNull { it.index == playback.primaryIndex }?.player?.name ?: "?"
        val following = ReplayViewer.following
        val seat = when {
            following != null -> "§7riding §f$following"
            !ReplayViewer.hasBodies -> "§7through §f$perspective §8(free cam · no bodies recorded)"
            else -> "§7through §f$perspective §8(free cam)"
        }
        g.text(font, seat, x + 8, y + 26, Ui.TEXT_DIM)
        Ui.textRight(
            g, font,
            if (ReplayViewer.ended) HINT_ENDED else HINT,
            x + width - 8, y + 26, Ui.TEXT_DIM,
        )
    }

    /** `1×` rather than `1.0×`, and `0.25×` where the fraction matters. */
    private fun trimSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

    private fun colorOf(type: ReplayEventType): Int = when (type) {
        ReplayEventType.CLAIM -> Ui.ACCENT
        ReplayEventType.DEATH -> Ui.LOSS
        ReplayEventType.FORFEIT -> Ui.LOSS
        ReplayEventType.GAME_START, ReplayEventType.GAME_END -> Ui.WHITE
        else -> Ui.TEXT_DIM
    }

    private const val HEIGHT = 40
    private const val BAR_HEIGHT = 4
    /**
     * One key, and it opens a panel of buttons rather than being a control itself.
     * See [ReplayControlsScreen] for why the controls are not bindings.
     */
    private const val HINT = "§8press R for controls"

    private const val HINT_ENDED = "§6ended §8· press R for controls"
}
