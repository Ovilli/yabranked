package dev.yabranked.client.replay

import dev.yabranked.client.Sfx
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * The replay's controls, as buttons.
 *
 * Everything here used to be a key mapping, because [ReplayHud] draws in-world and
 * an in-world overlay cannot take a click — there is no screen between the viewer
 * and the world to receive one. That reasoning was sound and the conclusion was
 * wrong: the answer is a screen you can open, not a control surface nobody can
 * find. Nine bindings is a manual, and a replay viewer whose controls are only
 * discoverable by reading one is a replay viewer people press the wrong key in.
 *
 * It deliberately does **not** pause the game ([isPauseScreen] is false), so the
 * recording keeps playing while it is open and the buttons act on something that
 * is moving. The world stays visible behind it for the same reason — this is a
 * panel over a match, not a menu instead of one.
 */
class ReplayControlsScreen : ScaledScreen(Component.literal("Replay Controls")) {

    /** Rebuilt every frame from the layout, like the other list screens. */
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val run: () -> Unit)

    private val hits = mutableListOf<Hit>()

    /** Scrub bar geometry, kept from the draw so a click can be mapped onto it. */
    private var barX = 0
    private var barY = 0
    private var barW = 0

    override fun layout() {
        addRenderableWidget(
            RankedButton(
                width / 2 - 100, height - 26, 200, 20,
                Component.literal("Close (R reopens)"), Ui.ICON_BACK,
            ) { onClose() }
        )
    }

    /** The recording plays on while this is open; that is the point of it. */
    override fun isPauseScreen(): Boolean = false

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // No blur and no scrim: the match behind this is the thing being controlled.
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        hits.clear()
        val playback = ReplayViewer.playback ?: return onClose()

        val w = (width - 60).coerceIn(280, 460)
        val x = (width - w) / 2
        val panelH = 132
        val y = height - panelH - 40

        Ui.panel(g, x, y, w, panelH)

        val meta = playback.meta
        val at = ((playback.positionMillis - meta.gameStartMillis) / 1000).coerceAtLeast(0)
        val end = ((playback.endMillis - meta.gameStartMillis) / 1000).coerceAtLeast(0)
        g.text(font, "§lREPLAY", x + 8, y + 6, Ui.ACCENT)
        Ui.textRight(g, font, "${Ui.duration(at)} / ${Ui.duration(end)}", x + w - 8, y + 6, Ui.TEXT_DIM)

        // --- scrub bar ---
        barX = x + 8
        barY = y + 20
        barW = w - 16
        val span = (playback.endMillis - meta.gameStartMillis).coerceAtLeast(1)
        val progress = ((playback.positionMillis - meta.gameStartMillis).toFloat() / span).coerceIn(0f, 1f)
        g.fill(barX, barY, barX + barW, barY + 5, Ui.SLOT_BG)
        g.fill(barX, barY, barX + (barW * progress).toInt(), barY + 5, Ui.ACCENT)
        for (event in meta.events) {
            val tick = barX + (barW * (event.atSeconds * 1000f / span).coerceIn(0f, 1f)).toInt()
            g.fill(tick, barY - 2, tick + 1, barY + 7, Ui.WHITE)
        }

        // --- transport ---
        var bx = x + 8
        val row = y + 32
        bx = button(g, bx, row, "« 10s", mouseX, mouseY) { ReplayViewer.nudge(-10_000) }
        bx = button(
            g, bx, row,
            if (ReplayViewer.ended) "Ended" else if (playback.paused) "▶ Play" else "❚❚ Pause",
            mouseX, mouseY,
        ) { ReplayViewer.togglePause() }
        bx = button(g, bx, row, "10s »", mouseX, mouseY) { ReplayViewer.nudge(10_000) }
        bx = button(g, bx, row, "${trimSpeed(playback.speed)}×", mouseX, mouseY) { ReplayViewer.cycleSpeed() }
        if (ReplayViewer.hasEvents) {
            bx = button(g, bx, row, "‹ moment", mouseX, mouseY) { ReplayViewer.jumpEvent(forward = false) }
            button(g, bx, row, "moment ›", mouseX, mouseY) { ReplayViewer.jumpEvent(forward = true) }
        }

        // --- who to watch ---
        val people = ReplayViewer.visiblePlayers()
        g.text(font, "§7Players", x + 8, y + 54, Ui.TEXT_DIM)
        if (people.isEmpty()) {
            g.text(
                font,
                if (ReplayViewer.hasBodies) "§8nobody in view right now"
                else "§8this recording has no player tracks",
                x + 8, y + 66, Ui.TEXT_DIM,
            )
        } else {
            var py = y + 66
            for (person in people.take(3)) {
                val name = person.gameProfile.name
                g.text(font, if (ReplayViewer.following == name) "§f$name" else "§7$name", x + 8, py + 3, Ui.WHITE)
                var right = x + w - 8
                right = buttonRight(g, right, py, "Teleport", mouseX, mouseY) { ReplayViewer.teleportTo(name) }
                buttonRight(
                    g, right, py,
                    if (ReplayViewer.following == name) "Unfollow" else "Follow",
                    mouseX, mouseY,
                ) { ReplayViewer.toggleFollow(name) }
                py += 16
            }
        }

        button(g, x + 8, y + panelH - 22, "Stop watching", mouseX, mouseY) {
            ReplayViewer.close()
        }
    }

    private fun trimSpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

    /** A button anchored at its left edge; returns the x to place the next one at. */
    private fun button(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        label: String,
        mouseX: Int,
        mouseY: Int,
        run: () -> Unit,
    ): Int {
        val w = font.width(label) + 12
        draw(g, x, y, w, label, mouseX, mouseY, run)
        return x + w + 4
    }

    /** The same, anchored at its right edge; returns the x its left edge reached. */
    private fun buttonRight(
        g: GuiGraphicsExtractor,
        right: Int,
        y: Int,
        label: String,
        mouseX: Int,
        mouseY: Int,
        run: () -> Unit,
    ): Int {
        val w = font.width(label) + 12
        draw(g, right - w, y, w, label, mouseX, mouseY, run)
        return right - w - 4
    }

    private fun draw(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        w: Int,
        label: String,
        mouseX: Int,
        mouseY: Int,
        run: () -> Unit,
    ) {
        val h = 14
        val hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        g.fill(x, y, x + w, y + h, if (hovered) Ui.BUTTON_BG_LIT else Ui.BUTTON_BG)
        g.fill(x, y, x + w, y + 1, Ui.BUTTON_BORDER)
        g.fill(x, y + h - 1, x + w, y + h, Ui.BUTTON_BORDER)
        g.centeredText(font, label, x + w / 2, y + 3, if (hovered) Ui.WHITE else Ui.TEXT_SOFT)
        hits += Hit(x, y, w, h, run)
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        for (hit in hits) {
            if (event.x() >= hit.x && event.x() < hit.x + hit.w &&
                event.y() >= hit.y && event.y() < hit.y + hit.h
            ) {
                Sfx.select()
                hit.run()
                return true
            }
        }
        // Anywhere on the bar seeks there. A timeline you can see and cannot click
        // is a progress bar, and skipping a twenty-minute match ten seconds at a
        // time is not seeking.
        val playback = ReplayViewer.playback
        if (playback != null && barW > 0 &&
            event.x() >= barX && event.x() <= barX + barW &&
            event.y() >= barY - 4 && event.y() <= barY + 9
        ) {
            val fraction = ((event.x() - barX) / barW.toDouble()).coerceIn(0.0, 1.0)
            val span = (playback.endMillis - playback.meta.gameStartMillis).coerceAtLeast(1)
            playback.seek((playback.meta.gameStartMillis + span * fraction).toInt())
            Sfx.tick()
            return true
        }
        return super.onMouseClicked(event, doubled)
    }
}
