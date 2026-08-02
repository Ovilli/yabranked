package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Full-screen MMR history chart — the "expand" view opened from the small chart
 * on [MatchHistoryScreen]. Same [Ui.eloChart] renderer, just given most of the
 * screen so the trend, gridlines and dated ticks are actually readable.
 *
 * The full series is often longer than the plot can usefully resolve, so this
 * screen owns a **window** into it: [zoom] is how many points are drawn and
 * [offset] is where that window starts. The wheel zooms about the cursor, drag
 * (or the arrow keys) pans, and the scrollbar underneath shows where in the
 * season the window sits. The window is always clamped to the series, so there
 * is no state in which the plot is empty or scrolled past the last match.
 */
class MmrChartScreen(
    private val parent: Screen?,
    private val points: List<Ui.ChartPoint>,
    private val subtitle: String,
) : ScaledScreen(Component.literal("MMR History")) {

    private var openedAt = 0L

    private val opened = FirstInit()

    /** Points inside the window. Never below [MIN_ZOOM], never above the series. */
    private var zoom = points.size

    /** Index of the window's first point. */
    private var offset = 0

    /** Where a drag-pan started: mouse x and the [offset] it began from. */
    private var dragFromX = -1.0
    private var dragFromOffset = 0

    /** Plot geometry from the last frame, so input can hit-test against it. */
    private var plotX = 0
    private var plotY = 0
    private var plotW = 0
    private var plotH = 0

    override fun layout() {
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        // Buttons for every gesture, not just the wheel and the drag.
        //
        // A chart you cannot pan until you have first zoomed in reads as a chart
        // that does nothing: at the default zoom the whole series is on screen,
        // so dragging is a no-op *by definition*, and the wheel is the only way
        // out of that state. If the wheel does not reach us — a trackpad sending
        // horizontal deltas, a bound scroll, a cursor a few pixels off the plot
        // — there was no second way in at all.
        // Plain ASCII glyphs: the default font has no guaranteed "−" or "⟨", and
        // a missing-glyph box on the only visible zoom control is worse than a
        // hyphen. Tooltips carry the words, so the narrator gets them too.
        val row = height - 50
        chartButton(width / 2 - 100, row, "-", "Zoom out") { stepZoom(out = true) }
        chartButton(width / 2 - 78, row, "+", "Zoom in") { stepZoom(out = false) }
        chartButton(width / 2 - 56, row, "<", "Pan left") { pan(-panStep()) }
        chartButton(width / 2 - 34, row, ">", "Pan right") { pan(panStep()) }
        addRenderableWidget(
            RankedButton(width / 2 - 10, row, 110, 18, Component.literal("Reset"), Ui.ICON_REFRESH) { resetZoom() }
        ).tip("Show the whole series again")
        opened.once { Sfx.open() }
        clamp()
    }

    /** One square control on the chart's toolbar. */
    private fun chartButton(x: Int, y: Int, glyph: String, label: String, action: () -> Unit) {
        addRenderableWidget(
            RankedButton(x, y, 20, 18, Component.literal(glyph), onPress = action)
        ).tip(label)
    }

    /** Points one pan press moves: a quarter window, at least one point. */
    private fun panStep(): Int = (zoom / 4).coerceAtLeast(1)

    private fun pan(by: Int) {
        offset += by
        clamp()
        Sfx.tick()
    }

    /**
     * One zoom notch.
     *
     * Forced to move by at least one point: `round(zoom * 1.25)` returns the
     * same number for a short series, so on a handful of rated matches every
     * press did nothing and the chart looked frozen.
     */
    private fun stepZoom(out: Boolean) {
        val before = zoom
        val scaled = Math.round(zoom * (if (out) ZOOM_STEP else 1.0 / ZOOM_STEP)).toInt()
        zoom = if (out) maxOf(scaled, before + 1) else minOf(scaled, before - 1)
        clamp()
        if (zoom != before) {
            // Keep the middle of the window where it was, so zooming does not
            // walk the view toward the start of the season.
            offset += (before - zoom) / 2
            clamp()
        }
        Sfx.tick()
    }

    private fun resetZoom() {
        zoom = points.size
        offset = 0
        Sfx.tick()
    }

    /** Whether the window is smaller than the series, i.e. panning means anything. */
    private val zoomed: Boolean get() = zoom < points.size

    /**
     * Keep the window inside the series.
     *
     * Both fields are written from the wheel, the drag and the keyboard, so the
     * clamp lives here rather than at each of them — an off-by-one at any one
     * site would otherwise index past the end of the list while rendering.
     */
    private fun clamp() {
        zoom = zoom.coerceIn(MIN_ZOOM.coerceAtMost(points.size), points.size.coerceAtLeast(1))
        offset = offset.coerceIn(0, (points.size - zoom).coerceAtLeast(0))
    }

    /** The slice currently on screen. */
    private fun window(): List<Ui.ChartPoint> {
        if (points.isEmpty()) return points
        val end = (offset + zoom).coerceAtMost(points.size)
        return points.subList(offset.coerceIn(0, end), end)
    }

    private fun inPlot(x: Double, y: Double): Boolean =
        x >= plotX && x < plotX + plotW && y >= plotY && y < plotY + plotH

    /**
     * Zoom about the cursor rather than about the window's start: the point
     * under the mouse is the one the player is looking at, so it is the one that
     * has to stay put as the window tightens.
     */
    override fun onMouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        if (points.size < 2) return true

        // Accepted anywhere on the screen, not only over the plot rectangle.
        // Requiring the cursor to be inside the chart made the summary strip,
        // the hint line and the whole area around the buttons into a silent dead
        // zone — the wheel simply did nothing, with no way to tell that from
        // "zooming is not implemented".
        //
        // Some wheels and most trackpads report the gesture on the horizontal
        // axis, so both are read; the vertical one wins when both are present.
        val amount = if (vAmount != 0.0) vAmount else hAmount
        if (amount == 0.0) return true

        // Anchor on the cursor when it is over the plot, on the middle of the
        // window when it is not — zooming from the margin should not drag the
        // view off to one end.
        val anchorFrac = when {
            plotW > 0 && inPlot(mouseX, mouseY) -> ((mouseX - plotX) / plotW).coerceIn(0.0, 1.0)
            else -> 0.5
        }
        val anchorIndex = offset + anchorFrac * (zoom - 1)

        val before = zoom
        val out = amount < 0
        val scaled = Math.round(zoom * (if (out) ZOOM_STEP else 1.0 / ZOOM_STEP)).toInt()
        // At least one point per notch: rounding alone leaves a short series
        // stuck on the same number however hard the wheel is turned.
        zoom = if (out) maxOf(scaled, before + 1) else minOf(scaled, before - 1)
        clamp()
        if (zoom == before) return true

        offset = Math.round(anchorIndex - anchorFrac * (zoom - 1)).toInt()
        clamp()
        Sfx.tick()
        return true
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.onMouseClicked(event, doubled)) return true
        if (event.button() == 0 && inPlot(event.x(), event.y())) {
            dragFromX = event.x()
            dragFromOffset = offset
            return true
        }
        return false
    }

    override fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (dragFromX < 0 || plotW <= 0 || zoom >= points.size) return super.onMouseDragged(event, dragX, dragY)
        // One plot-width of drag pans exactly one window, so the chart tracks the
        // cursor instead of moving at some fixed pixels-per-point rate that feels
        // wrong at every zoom level but one.
        val movedPoints = ((dragFromX - event.x()) / plotW * zoom).toInt()
        offset = dragFromOffset + movedPoints
        clamp()
        return true
    }

    override fun onMouseReleased(event: MouseButtonEvent): Boolean {
        dragFromX = -1.0
        return super.onMouseReleased(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val step = (zoom / 4).coerceAtLeast(1)
        when (event.key()) {
            GLFW.GLFW_KEY_LEFT -> { offset -= step; clamp(); return true }
            GLFW.GLFW_KEY_RIGHT -> { offset += step; clamp(); return true }
            GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                zoom = Math.round(zoom * ZOOM_STEP).toInt(); clamp(); return true
            }
            GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                zoom = Math.round(zoom / ZOOM_STEP).toInt().coerceAtLeast(MIN_ZOOM); clamp(); return true
            }
            GLFW.GLFW_KEY_0, GLFW.GLFW_KEY_KP_0 -> { resetZoom(); return true }
        }
        return super.keyPressed(event)
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()

        val centerX = width / 2
        // The subtitle used to be drawn below the plate rather than inside it,
        // which is why this screen's plate was the only 240-wide one: the line
        // it belonged to was not being measured against it.
        Ui.title(g, font, centerX, "§lMMR HISTORY", caption = subtitle, width = 240)

        // Chart takes the middle band, capped so it stays a sensible shape on very
        // wide/tall windows. Room is left at the bottom for the range scrollbar.
        val marginX = maxOf(24, (width - 520) / 2)
        val chartX = marginX
        val chartW = width - marginX * 2
        val chartTop = 54
        val chartH = (height - chartTop - 84).coerceIn(90, 260)

        plotX = chartX
        plotY = chartTop
        plotW = chartW
        plotH = chartH

        val shown = window()
        Ui.panel(g, chartX - 2, chartTop - 2, chartW + 4, chartH + 4)
        Ui.eloChart(g, font, chartX, chartTop, chartW, chartH, shown, mouseX, mouseY)

        // Range bar: where the window sits in the whole series.
        //
        // Drawn even when the window is the whole series — as a full-width bar.
        // Hiding it there meant the one state in which panning does nothing was
        // also the state with no indication that panning exists, so a chart on
        // first open looked inert rather than zoomed all the way out.
        if (zoomed) {
            Ui.scrollbarHorizontal(
                g,
                chartX, chartTop + chartH + 4, chartW,
                total = points.size, visible = zoom, offset = offset,
            )
        } else {
            g.fill(chartX, chartTop + chartH + 4, chartX + chartW, chartTop + chartH + 6, Ui.TRACK)
            g.fill(chartX, chartTop + chartH + 4, chartX + chartW, chartTop + chartH + 6, Ui.alpha(Ui.THUMB, 0x80))
        }

        // Summary strip below the chart, for the window rather than the series:
        // it describes what is on screen, which is the point of zooming in.
        val ratings = shown.map { it.rating }
        if (ratings.isNotEmpty()) {
            val peak = ratings.max()
            val low = ratings.min()
            val net = ratings.last() - ratings.first()
            val netTxt = if (net >= 0) "§a+$net" else "§c$net"
            val range = if (zoomed) "§7 (${offset + 1}–${offset + ratings.size} of ${points.size})" else ""
            val line = "§7Peak §f$peak   §7Low §f$low   §7Net $netTxt§7   Points §f${ratings.size}$range"
            g.centeredText(font, line, centerX, chartTop + chartH + 14, Ui.TEXT_DIM)
        }
        // The hint says what is possible *right now*: at full zoom there is
        // nothing to pan to, and telling the player to drag would be a lie.
        val hint = when {
            points.size < 2 -> "§8Not enough rated matches to chart"
            zoomed -> "§8scroll or −/+ to zoom · drag, ←/→ or the arrows to pan · 0 resets"
            else -> "§8showing all ${points.size} matches — scroll or press + to zoom in"
        }
        g.centeredText(font, hint, centerX, chartTop + chartH + 26, Ui.TEXT_FAINT)

        Ui.fadeIn(g, width, height, openedAt)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        /** Fewest points a window may hold; below two there is no line to read. */
        const val MIN_ZOOM = 2

        /** Multiplier per wheel notch. */
        const val ZOOM_STEP = 1.25
    }
}
