package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class MatchHistoryScreen(
    private val parent: Screen?,
) : ScaledScreen(Component.literal("Match History")) {

    private var entries: Loadable<List<MatchHistoryEntry>> = Loadable.Loading

    /** The way back from a failed read; see [dev.yabranked.client.ui.RetryCard]. */
    private val retry = dev.yabranked.client.ui.RetryCard { load() }

    /** Y of the first row, set during render so click hit-testing matches it
     *  (it shifts down when the trend chart is shown). */
    private var rowsTop = TOP

    /** First visible row index; driven by the mouse wheel. */
    private var scroll = 0

    /** True once the backend has answered with a short page; see [loadMore]. */
    private var atEnd = false

    /** True while a further page is in flight, so the button cannot stack them. */
    private var loadingMore = false
    /** The list's scrollbar, draggable; see [dev.yabranked.client.ui.Scrollbar]. */
    private val bar = dev.yabranked.client.ui.Scrollbar()


    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    private var search: EditBox? = null

    /** Keyboard-selected row index into the filtered list; -1 for none. */
    private var selected = -1

    /** Rows after the opponent-name search filter. */
    private fun filtered(list: List<MatchHistoryEntry>): List<MatchHistoryEntry> {
        val q = search?.value?.trim().orEmpty()
        return if (q.isEmpty()) list else list.filter { it.opponent.name.contains(q, ignoreCase = true) }
    }

    /** Rating trend points, oldest→newest, dropping matches that never counted. */
    private fun chartPoints(list: List<MatchHistoryEntry>): List<Ui.ChartPoint> =
        list.filter { it.rated && it.ratingAfter != null }
            .map { Ui.ChartPoint(it.ratingAfter!!, it.completedAt) }
            .reversed()

    private val opened = FirstInit()
    private val loaded = FirstInit()

    override fun layout() {
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        search = addRenderableWidget(
            EditBox(font, width / 2 - WIDTH / 2 + 14, 48, WIDTH - 14, 14, Component.literal("Search")).apply {
                setHint(Component.literal("Search opponents…"))
                setMaxLength(32)
                value = search?.value ?: ""
                setResponder { scroll = 0; selected = -1 }
            }
        )
        opened.once { Sfx.open() }

        val backend = RankedState.backend
        val profile = RankedState.profile
        if (backend == null || profile == null) {
            entries = Loadable.Failed("Not signed in")
            return
        }
        // Once per screen, not once per resize — see PlayerProfileScreen.
        loaded.once { load() }
    }

    /**
     * Fetch this player's history. Split out of [layout] so the retry on a
     * failed read has something to call; the once-per-screen guard stays there,
     * because a retry is a deliberate second attempt rather than a resize.
     */
    private fun load() {
        val backend = RankedState.backend ?: return
        val profile = RankedState.profile ?: return
        val minecraft = this.minecraft
        entries = Loadable.Loading
        atEnd = false
        YabRankedClient.workers.execute {
            val fetched = backend.fetchHistory(profile.uuid, limit = PAGE)
            minecraft.execute {
                entries = fetched.toLoadable()
                atEnd = (entries.valueOrNull?.size ?: 0) < PAGE
                // A failed read leaves the streak alone rather than zeroing it.
                entries.valueOrNull?.let { RankedState.winStreak = RankedState.currentWinStreak(it) }
            }
        }
    }

    /**
     * Append the next page.
     *
     * The history used to be one request for fifty rows and that was the whole
     * of it: a player with more matches than that simply could not reach them,
     * and nothing on screen said so. Rows already on screen are kept — this
     * grows the list rather than replacing it, so the scroll position stays put.
     */
    private fun loadMore() {
        val backend = RankedState.backend ?: return
        val profile = RankedState.profile ?: return
        val have = entries.valueOrNull ?: return
        if (loadingMore || atEnd) return
        loadingMore = true
        val minecraft = this.minecraft
        YabRankedClient.workers.execute {
            val fetched = backend.fetchHistory(profile.uuid, limit = PAGE, offset = have.size)
            minecraft.execute {
                loadingMore = false
                val page = (fetched as? BackendClient.Fetch.Ok)?.value
                if (page == null) {
                    RankedNotice.error("Could not load more matches", title = "History")
                    return@execute
                }
                // A short page is the end of the history; a page that adds
                // nothing is too, and both have to stop the button offering more.
                atEnd = page.size < PAGE
                if (page.isEmpty()) return@execute
                entries = Loadable.Loaded(have + page)
            }
        }
    }

    private fun resultColor(result: String) = when (result) {
        "win" -> Ui.WIN
        "loss" -> Ui.LOSS
        "draw" -> Ui.DRAW
        else -> Ui.TEXT_FAINT
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()

        val centerX = width / 2
        val left = centerX - WIDTH / 2
        Ui.title(g, font, centerX, "§lMATCH HISTORY")

        // magnifier in front of the search box — drawn on every state so it never
        // disappears while loading or on an error card.
        Ui.icon(g, Ui.ICON_SEARCH, left + 2, 50, 10, Ui.TEXT_DIM)

        val placeholder = entries.placeholder("Play a ranked match to see your history")
        if (placeholder != null) {
            // A first load gets row-shaped stubs instead of a line of text, so the
            // list's shape is on screen before its content is.
            if (entries is Loadable.Loading) drawLoadingSkeleton(g, centerX)
            else retry.draw(
                g, font, centerX, 74, placeholder,
                retryable = entries is Loadable.Failed,
                mouseX = mouseX, mouseY = mouseY,
            )
            Ui.fadeIn(g, width, height, openedAt)
            return
        }
        val list = entries.valueOrNull.orEmpty()

        // record summary across the fetched window, clear of the header plate
        val wins = list.count { it.result == "win" }
        val losses = list.count { it.result == "loss" }
        g.centeredText(font, "${wins}W · ${losses}L · last ${list.size}", centerX, 38, Ui.TEXT_DIM)

        val searching = !search?.value?.trim().isNullOrEmpty()

        // Rating trend chart (oldest → newest). Hidden while searching so the
        // filtered rows get the full height. History arrives newest-first, so
        // reverse it and drop matches that never counted (voided = null after).
        rowsTop = TOP
        val points = chartPoints(list)
        if (!searching && points.size >= 2) {
            Ui.eloChart(g, font, left, CHART_TOP, WIDTH, CHART_HEIGHT, points, mouseX, mouseY)
            // Expand affordance, tucked at the plot's top-right (clear of the
            // axis). Plain words, not a glyph: U+2922 is outside the default font's
            // coverage and rendered as a missing-character box, so the one cue
            // that the chart opens into a zoomable view read as an artefact.
            val hint = "click to zoom"
            g.text(font, "§8$hint", left + WIDTH - 32 - font.width(hint) - 2, CHART_TOP + 1, Ui.TEXT_FAINT)
            rowsTop = CHART_TOP + CHART_HEIGHT + 6
        }

        val shown = filtered(list)
        if (shown.isEmpty()) {
            g.centeredText(font, "§7No matches against that opponent", centerX, rowsTop + 8, Ui.TEXT_DIM)
            Ui.fadeIn(g, width, height, openedAt)
            return
        }
        val visible = visibleRows()
        selected = selected.coerceIn(-1, shown.size - 1)
        scroll = scroll.coerceIn(0, (shown.size - visible).coerceAtLeast(0))
        for (i in 0 until visible) {
            val index = i + scroll
            if (index >= shown.size) break
            val entry = shown[index]
            val y = rowsTop + i * ROW_HEIGHT
            val hovered = (mouseX in left..(left + WIDTH) && mouseY in y until (y + ROW_HEIGHT - 1)) || index == selected
            drawRow(g, left, y, entry, hovered)
        }
        bar.draw(g, left + WIDTH + 2, rowsTop, visible * ROW_HEIGHT, shown.size, visible, scroll, mouseX, mouseY)

        // Reaching the bottom asks for the next page. A button would need room
        // this screen does not have — the list already runs to the Back button —
        // and scrolling to the end is the gesture that means "more" anyway. It
        // fires while searching too: the filter only sees rows that have been
        // fetched, so a name with no hits on page one may well have them later.
        if (!atEnd && scroll + visible >= shown.size) loadMore()
        if (loadingMore) {
            g.centeredText(font, "§8loading more…", centerX, rowsTop + visible * ROW_HEIGHT + 2, Ui.TEXT_FAINT)
        }

        Ui.fadeIn(g, width, height, openedAt)
    }

    /** Rows that fit between [rowsTop] and the Back button. The single source
     *  for the row count, so render, hit-testing and keyboard scrolling can
     *  never disagree about which row is where. */
    private fun visibleRows() = ((height - BOTTOM_CONTROLS - rowsTop) / ROW_HEIGHT).coerceAtLeast(1)

    private fun ensureVisible() {
        val v = visibleRows()
        if (selected < scroll) scroll = selected
        else if (selected >= scroll + v) scroll = selected - v + 1
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (search?.isFocused == true) return super.keyPressed(event)
        val shown = filtered(entries.valueOrNull ?: return super.keyPressed(event))
        when (event.key()) {
            GLFW.GLFW_KEY_DOWN -> {
                if (shown.isNotEmpty()) { selected = (selected + 1).coerceIn(0, shown.size - 1); ensureVisible() }
                return true
            }
            GLFW.GLFW_KEY_UP -> {
                if (shown.isNotEmpty()) { selected = (selected - 1).coerceAtLeast(0); ensureVisible() }
                return true
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                shown.getOrNull(selected)?.let { entry ->
                    Sfx.select()
                    minecraft.setScreenAndShow(MatchDetailScreen(this, entry))
                }
                return true
            }
        }
        return super.keyPressed(event)
    }

    private fun drawLoadingSkeleton(g: GuiGraphicsExtractor, centerX: Int) {
        val left = centerX - WIDTH / 2
        val sk = Ui.SKELETON
        // Draw a few placeholder rows
        for (i in 0 until 5) {
            val y = TOP + i * ROW_HEIGHT
            Ui.row(g, left, y, WIDTH, ROW_HEIGHT - 1)
            // label stub
            g.fill(left + 8, y + 4, left + 36, y + 9, sk)
            // opponent name stub
            g.fill(left + 44, y + 4, left + 140, y + 9, sk)
            // delta and duration stubs (right aligned area)
            g.fill(left + WIDTH - 70, y + 4, left + WIDTH - 10, y + 9, sk)
        }
        g.centeredText(font, "§7Loading…", centerX, TOP + 5 * ROW_HEIGHT + 8, Ui.TEXT_DIM)
    }

    private fun drawRow(g: GuiGraphicsExtractor, left: Int, y: Int, entry: MatchHistoryEntry, hovered: Boolean) {
        val color = resultColor(entry.result)

        Ui.row(g, left, y, WIDTH, ROW_HEIGHT - 1)
        // hover feedback signals the row is clickable (opens the opponent profile)
        if (hovered) g.fill(left + 2, y + 1, left + WIDTH - 2, y + ROW_HEIGHT - 2, Ui.HOVER)
        Ui.accentBar(g, left, y, ROW_HEIGHT - 1, color)

        val textY = y + 4
        // Win/loss get a tinted glyph; draw/void keep a short text tag since they
        // have no icon. The glyph reads faster than the word at a glance.
        when (entry.result) {
            "win" -> Ui.icon(g, Ui.ICON_WIN, left + 8, textY - 1, 10, color)
            "loss" -> Ui.icon(g, Ui.ICON_LOSS, left + 8, textY - 1, 10, color)
            "draw" -> g.text(font, "DRAW", left + 8, textY, color)
            else -> g.text(font, "§8—", left + 8, textY, color)
        }
        // "vs " + optional flag + opponent name
        g.text(font, "vs", left + 44, textY, Ui.WHITE)
        var nx = left + 44 + font.width("vs ")
        if (RankedState.showFlags) {
            entry.opponent.country?.let {
                Ui.flagIcon(g, nx, textY, it, 8)
                nx += 10
            }
        }
        // Clamp the name so it never runs into the rating-delta / time columns.
        val nameMax = (left + WIDTH - 66) - nx
        g.text(font, Ui.fit(font, entry.opponent.name, nameMax), nx, textY, Ui.WHITE)

        // Rating delta; a casual match says so rather than showing a swing it
        // never had, and a voided one gets a dash.
        val delta = when {
            !entry.rated -> "§8casual"
            entry.ratingAfter == null -> "§8—"
            else -> {
                val diff = entry.ratingAfter!! - entry.ratingBefore
                if (diff >= 0) "§a+$diff" else "§c$diff"
            }
        }
        Ui.textRight(g, font, delta, left + WIDTH - 62, textY, Ui.TEXT_DIM)
        // when the match was played, so the list reads as a timeline
        val ago = Ui.relativeTime(entry.completedAt)
        if (ago.isNotEmpty()) Ui.textRight(g, font, "§8$ago", left + WIDTH - 10, textY, Ui.TEXT_FAINT)
    }

    override fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        bar.dragged(event.y())?.let { scroll = it; return true }
        return super.onMouseDragged(event, dragX, dragY)
    }

    override fun onMouseReleased(event: MouseButtonEvent): Boolean {
        bar.released()
        return super.onMouseReleased(event)
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        bar.clicked(event.x(), event.y(), scroll)?.let { scroll = it; return true }
        if (super.onMouseClicked(event, doubled)) return true
        if (retry.clicked(event.x(), event.y())) return true
        if (event.button() != 0) return false
        val shown = filtered(entries.valueOrNull ?: return false)

        val left = width / 2 - WIDTH / 2
        val mouseX = event.x()
        val mouseY = event.y()
        if (mouseX < left || mouseX > left + WIDTH) return false

        // Click the trend chart to open the full-screen version.
        val searching = !search?.value?.trim().isNullOrEmpty()
        val points = chartPoints(entries.valueOrNull.orEmpty())
        if (!searching && points.size >= 2 &&
            mouseY >= CHART_TOP && mouseY < CHART_TOP + CHART_HEIGHT
        ) {
            Sfx.select()
            val name = RankedState.profile?.name ?: "Your"
            minecraft.setScreenAndShow(MmrChartScreen(this, points, "$name · last ${points.size} rated matches"))
            return true
        }

        val visible = visibleRows()
        for (i in 0 until visible) {
            val index = i + scroll
            if (index >= shown.size) break
            val y = rowsTop + i * ROW_HEIGHT
            if (mouseY >= y && mouseY < y + ROW_HEIGHT - 1) {
                val entry = shown[index]
                Sfx.select()
                minecraft.setScreenAndShow(MatchDetailScreen(this, entry))
                return true
            }
        }
        return false
    }

    override fun onMouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        val before = scroll
        if (vAmount > 0) scroll = (scroll - 1).coerceAtLeast(0) else if (vAmount < 0) scroll += 1
        if (scroll != before) Sfx.tick()
        return true
    }

    override fun onClose() {
        // null parent means the screen was opened by keybind mid-game:
        // fall back to vanilla behaviour, which returns to the game
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        /**
         * Rows a page. The backend caps a single read at fifty, so this is that
         * cap rather than a preference — asking for more would silently get
         * fifty and make every page look like the last one.
         */
        const val PAGE = 50

        const val WIDTH = 280
        const val TOP = 68 // rows top when the chart is hidden (below the search box)
        const val ROW_HEIGHT = 14
        const val CHART_TOP = 68 // chart sits below the search box (y=48..62)
        const val CHART_HEIGHT = 48

        /** Height reserved at the bottom for the Back button (`height - 28`)
         *  plus its margin, so the last row never lands on it. */
        const val BOTTOM_CONTROLS = 40
    }
}
