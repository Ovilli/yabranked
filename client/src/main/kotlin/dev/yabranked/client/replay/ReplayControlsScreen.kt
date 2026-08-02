package dev.yabranked.client.replay

import dev.yabranked.client.Sfx
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.ReplayStreamInfo
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

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
 *
 * The panel is sized from what it has to show rather than fixed, because the two
 * lists on it are as long as the match was wide: a 4v4 has eight streams to
 * choose between and seven other players to follow.
 */
class ReplayControlsScreen : ScaledScreen(Component.literal("Replay Controls")) {

    /** Rebuilt every frame from the layout, like the other list screens. */
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val run: () -> Unit)

    private val hits = mutableListOf<Hit>()

    /** Scrub bar geometry, kept from the draw so a click can be mapped onto it. */
    private var barX = 0
    private var barY = 0
    private var barW = 0

    /**
     * Where the playhead has been dragged to but not yet released, or null when
     * nothing is being dragged.
     *
     * The drag only *previews*: a backwards seek is a restart-and-fast-forward,
     * because a packet stream is deltas and there is no packet that puts a block
     * back. Seeking on every drag event would replay the match from zero once per
     * pixel of mouse travel, which is not slow, it is a hung client. So the bar
     * and the clock follow the cursor and the recording is moved once, on release.
     */
    private var scrubTarget: Int? = null

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
        val meta = playback.meta

        val w = (width - 60).coerceIn(280, 460)
        val x = (width - w) / 2
        val inner = w - 16

        // Both lists are as long as the match was wide, so the panel is measured
        // before it is drawn rather than given a height that fits a 1v1.
        val people = ReplayViewer.visiblePlayers()
        val peopleRows = people.size.coerceAtLeast(1)
        val streams = meta.streams
        val perspectiveRows = perspectiveRows(streams, inner)
        val panelH = TRANSPORT_BLOCK +
            (if (perspectiveRows > 0) LABEL + perspectiveRows * ROW + 4 else 0) +
            LABEL + peopleRows * ROW + FOOTER
        // Clamped rather than allowed to run off the top: an eight-stream panel is
        // taller than a small viewport has under it.
        val y = (height - panelH - 40).coerceAtLeast(4)

        Ui.panel(g, x, y, w, panelH)

        var cy = y + 6
        // While a drag is in flight the whole panel reads from where the cursor
        // is, not from where the recording still is — a scrub bar whose clock
        // disagrees with its own handle is unusable for finding a moment.
        val shown = scrubTarget ?: playback.positionMillis
        val at = ((shown - meta.gameStartMillis) / 1000).coerceAtLeast(0)
        val end = ((playback.endMillis - meta.gameStartMillis) / 1000).coerceAtLeast(0)
        g.text(font, "§lREPLAY", x + 8, cy, Ui.ACCENT)
        Ui.textRight(g, font, "${Ui.duration(at)} / ${Ui.duration(end)}", x + w - 8, cy, Ui.TEXT_DIM)
        cy += 14

        // --- scrub bar ---
        barX = x + 8
        barY = cy
        barW = inner
        val span = (playback.endMillis - meta.gameStartMillis).coerceAtLeast(1)
        val progress = ((shown - meta.gameStartMillis).toFloat() / span).coerceIn(0f, 1f)
        g.fill(barX, barY, barX + barW, barY + 5, Ui.SLOT_BG)
        g.fill(barX, barY, barX + (barW * progress).toInt(), barY + 5, Ui.ACCENT)
        for (event in meta.events) {
            val tick = barX + (barW * (event.atSeconds * 1000f / span).coerceIn(0f, 1f)).toInt()
            g.fill(tick, barY - 2, tick + 1, barY + 7, Ui.WHITE)
        }
        // A grabbable head, so the bar reads as something to drag rather than as a
        // progress indicator that happens to accept a click.
        val head = barX + (barW * progress).toInt()
        g.fill(head - 1, barY - 3, head + 2, barY + 8, if (scrubTarget != null) Ui.WHITE else Ui.TEXT_SOFT)
        cy += 12

        // --- transport ---
        var bx = x + 8
        bx = button(g, bx, cy, "« 10s", mouseX, mouseY) { ReplayViewer.nudge(-10_000) }
        bx = button(
            g, bx, cy,
            if (ReplayViewer.ended) "Ended" else if (playback.paused) "▶ Play" else "❚❚ Pause",
            mouseX, mouseY,
        ) { ReplayViewer.togglePause() }
        bx = button(g, bx, cy, "10s »", mouseX, mouseY) { ReplayViewer.nudge(10_000) }
        bx = button(g, bx, cy, "${trimSpeed(playback.speed)}×", mouseX, mouseY) { ReplayViewer.cycleSpeed() }
        if (ReplayViewer.hasEvents) {
            bx = button(g, bx, cy, "‹ moment", mouseX, mouseY) { ReplayViewer.jumpEvent(forward = false) }
            button(g, bx, cy, "moment ›", mouseX, mouseY) { ReplayViewer.jumpEvent(forward = true) }
        }
        cy += 20

        // --- whose stream ---
        //
        // A stream holds what one client was *sent*, so which one you are watching
        // decides what exists at all — chunks the others never loaded, players the
        // others could not see. It was asked once on the way in and then never
        // again, which made "what did the other one see?" a question you could only
        // answer by leaving the replay and opening it a second time.
        if (perspectiveRows > 0) {
            g.text(font, "§7Watching through", x + 8, cy, Ui.TEXT_DIM)
            cy += LABEL
            var px = x + 8
            for (stream in streams) {
                val label = streamLabel(stream)
                val bw = font.width(label) + 12
                if (px + bw > x + w - 8) {
                    px = x + 8
                    cy += ROW
                }
                val current = stream.index == playback.primaryIndex
                draw(g, px, cy, bw, label, mouseX, mouseY, selected = current) {
                    if (!current) ReplayViewer.switchPerspective(stream.index)
                }
                px += bw + 4
            }
            cy += ROW + 4
        }

        // --- who to watch ---
        g.text(font, "§7Players", x + 8, cy, Ui.TEXT_DIM)
        cy += LABEL
        if (people.isEmpty()) {
            g.text(
                font,
                if (ReplayViewer.hasBodies) "§8nobody in view right now"
                else "§8this recording has no player tracks",
                x + 8, cy + 3, Ui.TEXT_DIM,
            )
        } else {
            for (person in people) {
                val name = person.gameProfile.name
                g.text(font, if (ReplayViewer.following == name) "§f$name" else "§7$name", x + 8, cy + 3, Ui.WHITE)
                var right = x + w - 8
                right = buttonRight(g, right, cy, "Teleport", mouseX, mouseY) { ReplayViewer.teleportTo(name) }
                buttonRight(
                    g, right, cy,
                    if (ReplayViewer.following == name) "Unfollow" else "Follow",
                    mouseX, mouseY,
                ) { ReplayViewer.toggleFollow(name) }
                cy += ROW
            }
        }

        button(g, x + 8, y + panelH - 22, "Stop watching", mouseX, mouseY) {
            ReplayViewer.close()
        }
        Ui.textRight(
            g, font, "§8space · ← → · tab",
            x + w - 8, y + panelH - 19, Ui.TEXT_FAINT,
        )
    }

    /** `name` plus the one thing about a stream a viewer has to know before picking it. */
    private fun streamLabel(stream: ReplayStreamInfo): String =
        stream.player.name + if (stream.truncated) " (partial)" else ""

    /**
     * Rows the perspective buttons wrap onto, measured the same way they are laid
     * out — the panel's height is decided before they are drawn, and a second
     * arithmetic for it is a second thing to keep in step.
     *
     * Zero when there is nothing to choose between: a 1v1 watched from the only
     * stream that is not yours has one button, and one button is not a choice.
     */
    private fun perspectiveRows(streams: List<ReplayStreamInfo>, inner: Int): Int {
        if (streams.size <= 1) return 0
        var rows = 1
        var px = 0
        for (stream in streams) {
            val bw = font.width(streamLabel(stream)) + 12
            if (px + bw > inner) {
                rows++
                px = 0
            }
            px += bw + 4
        }
        return rows
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
        draw(g, x, y, w, label, mouseX, mouseY, run = run)
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
        draw(g, right - w, y, w, label, mouseX, mouseY, run = run)
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
        selected: Boolean = false,
        run: () -> Unit,
    ) {
        val h = 14
        val hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h
        g.fill(x, y, x + w, y + h, if (hovered) Ui.BUTTON_BG_LIT else Ui.BUTTON_BG)
        // The one you are already watching is marked rather than disabled: it is
        // the answer to "which of these am I looking at", which is worth drawing
        // even though pressing it does nothing.
        if (selected) g.fill(x, y, x + w, y + h, Ui.SELECTION)
        g.fill(x, y, x + w, y + 1, if (selected) Ui.ACCENT else Ui.BUTTON_BORDER)
        g.fill(x, y + h - 1, x + w, y + h, if (selected) Ui.ACCENT else Ui.BUTTON_BORDER)
        g.centeredText(
            font, label, x + w / 2, y + 3,
            when {
                selected -> Ui.ACCENT
                hovered -> Ui.WHITE
                else -> Ui.TEXT_SOFT
            },
        )
        hits += Hit(x, y, w, h, run)
    }

    // --- input ---

    /** Where on the bar [x] is, as a position in the recording. */
    private fun positionAt(x: Double): Int? {
        val playback = ReplayViewer.playback ?: return null
        val fraction = ((x - barX) / barW.toDouble()).coerceIn(0.0, 1.0)
        val span = (playback.endMillis - playback.meta.gameStartMillis).coerceAtLeast(1)
        return (playback.meta.gameStartMillis + span * fraction).toInt()
    }

    /** True when [x],[y] is on the scrub bar, which is drawn thinner than it is hit. */
    private fun onBar(x: Double, y: Double): Boolean =
        barW > 0 && x >= barX && x <= barX + barW && y >= barY - 4 && y <= barY + 9

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
        if (onBar(event.x(), event.y())) {
            scrubTarget = positionAt(event.x())
            return true
        }
        return super.onMouseClicked(event, doubled)
    }

    /**
     * Dragging the playhead, which is the half of a scrub bar a click alone does
     * not give you: finding a moment is done by sweeping across it and watching
     * the clock, not by guessing an x and clicking again when it was wrong.
     *
     * Only the preview moves here; see [scrubTarget] for why the recording does
     * not follow until the button comes up.
     */
    override fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (scrubTarget != null) {
            scrubTarget = positionAt(event.x())
            return true
        }
        return super.onMouseDragged(event, dragX, dragY)
    }

    /** Where the drag ended is the seek. A click that never moved is a drag of zero. */
    override fun onMouseReleased(event: MouseButtonEvent): Boolean {
        val target = scrubTarget
        scrubTarget = null
        if (target != null) {
            ReplayViewer.playback?.seek(target)
            Sfx.tick()
            return true
        }
        return super.onMouseReleased(event)
    }

    /**
     * The transport keys, which is what a video player is expected to answer to.
     *
     * These are not key *mappings* — a mapping is global, and these only mean
     * anything while this panel has the keyboard. That is the difference between
     * the nine bindings this screen replaced and four keys that work where the
     * controls they duplicate are already visible.
     */
    override fun keyPressed(event: KeyEvent): Boolean {
        if (ReplayViewer.playback == null) return super.keyPressed(event)
        when (event.key()) {
            GLFW.GLFW_KEY_SPACE -> {
                ReplayViewer.togglePause()
                Sfx.tick()
                return true
            }
            GLFW.GLFW_KEY_LEFT -> {
                ReplayViewer.nudge(-10_000)
                return true
            }
            GLFW.GLFW_KEY_RIGHT -> {
                ReplayViewer.nudge(10_000)
                return true
            }
            GLFW.GLFW_KEY_TAB -> {
                ReplayViewer.cycleCamera()
                Sfx.tick()
                return true
            }
        }
        return super.keyPressed(event)
    }

    private companion object {
        /** A list row, and the height of a button in one. */
        const val ROW = 16

        /** A "§7Something" heading and the gap under it. */
        const val LABEL = 12

        /** Title line, scrub bar and transport row: fixed, and always present. */
        const val TRANSPORT_BLOCK = 6 + 14 + 12 + 20

        /** The gap under the last list, plus the Stop watching button. */
        const val FOOTER = 6 + 22
    }
}
