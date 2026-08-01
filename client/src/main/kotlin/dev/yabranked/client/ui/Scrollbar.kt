package dev.yabranked.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A scrollbar you can actually drag.
 *
 * [Ui.scrollbar] draws one and nothing more, which left six screens with a bar
 * that looks like a control and answers to nothing: the wheel worked, the thing
 * on screen representing the wheel's position did not. A bar that cannot be
 * grabbed is worse than no bar, because it invites the grab.
 *
 * Units are the caller's. A screen that scrolls in rows passes rows and gets
 * rows back; one that scrolls in pixels passes pixels. This class only ever maps
 * a position along the track onto that range, so it never has to know which.
 *
 * Owned by a screen, drawn every frame, and asked about clicks and drags:
 *
 * ```
 * private val bar = Scrollbar()
 * // in drawContent
 * bar.draw(g, x, top, height, total, visible, scroll)
 * // in onMouseClicked / onMouseDragged / onMouseReleased
 * bar.clicked(mx, my)?.let { scroll = it }
 * bar.dragged(my)?.let { scroll = it }
 * bar.released()
 * ```
 */
class Scrollbar {

    private var x = 0
    private var y = 0
    private var height = 0
    private var total = 0
    private var visible = 0

    /** Whether the thumb is currently held. Survives the cursor leaving the bar. */
    var dragging = false
        private set

    /**
     * Where in the thumb the drag started, so the thumb does not jump so that
     * its top snaps to the cursor the moment it is grabbed.
     */
    private var grabOffset = 0

    /** True while there is something to scroll; [draw] decides it each frame. */
    private val active: Boolean get() = height > 0 && total > visible

    private val maxScroll: Int get() = (total - visible).coerceAtLeast(0)

    private val thumbHeight: Int get() = maxOf(MIN_THUMB, height * visible / total.coerceAtLeast(1))

    /**
     * Record the geometry without drawing anything.
     *
     * Separate from [draw] so the hit-testing can be exercised without a running
     * game — everything below this line is arithmetic, and arithmetic is where a
     * scrollbar goes wrong in ways nobody notices until the list is long.
     */
    fun layout(x: Int, y: Int, height: Int, total: Int, visible: Int) {
        this.x = x
        this.y = y
        this.height = height
        this.total = total
        this.visible = visible
        if (!active) dragging = false
    }

    fun draw(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        height: Int,
        total: Int,
        visible: Int,
        scroll: Int,
        mouseX: Int = -1,
        mouseY: Int = -1,
    ) {
        layout(x, y, height, total, visible)
        if (!active) return

        val over = dragging || (mouseX >= x - GRAB_PAD && mouseX < x + WIDTH + GRAB_PAD &&
            mouseY >= y && mouseY < y + height)
        g.fill(x, y, x + WIDTH, y + height, Ui.TRACK)
        val top = thumbTop(scroll)
        g.fill(x, top, x + WIDTH, top + thumbHeight, if (over) Ui.WHITE else Ui.THUMB)
    }

    private fun thumbTop(scroll: Int): Int =
        y + if (maxScroll == 0) 0 else (height - thumbHeight) * scroll.coerceIn(0, maxScroll) / maxScroll

    /**
     * A press at [mouseX]/[mouseY]: the new scroll, or null when the bar was not
     * hit and the screen should go on handling the click itself.
     *
     * On the thumb this begins a drag and changes nothing. On the track it jumps
     * there — a click above the thumb meaning "further up" is what every
     * scrollbar does, and a page-step here would need a second concept of a page.
     */
    fun clicked(mouseX: Double, mouseY: Double, scroll: Int): Int? {
        if (!active) return null
        if (mouseX < x - GRAB_PAD || mouseX >= x + WIDTH + GRAB_PAD) return null
        if (mouseY < y || mouseY >= y + height) return null

        val top = thumbTop(scroll)
        if (mouseY >= top && mouseY < top + thumbHeight) {
            dragging = true
            grabOffset = (mouseY - top).toInt()
            return scroll
        }
        dragging = true
        grabOffset = thumbHeight / 2
        return scrollAt(mouseY)
    }

    /** The new scroll while dragging, or null when no drag is in progress. */
    fun dragged(mouseY: Double): Int? {
        if (!dragging || !active) return null
        return scrollAt(mouseY)
    }

    fun released() {
        dragging = false
    }

    /** Maps a cursor position on the track onto the scroll range. */
    private fun scrollAt(mouseY: Double): Int {
        val travel = height - thumbHeight
        if (travel <= 0) return 0
        val top = (mouseY - y - grabOffset).coerceIn(0.0, travel.toDouble())
        return (top * maxScroll / travel).toInt().coerceIn(0, maxScroll)
    }

    private companion object {
        const val WIDTH = 2

        /**
         * Pixels either side of the bar that still count as hitting it. Two
         * pixels is a fair target with a mouse and an unfair one at a high GUI
         * scale, where the whole screen is drawn small.
         */
        const val GRAB_PAD = 3
        const val MIN_THUMB = 10
    }
}
