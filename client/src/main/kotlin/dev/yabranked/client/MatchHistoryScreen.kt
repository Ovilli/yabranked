package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

class MatchHistoryScreen(
    private val parent: Screen?,
) : Screen(Component.literal("Match History")) {

    private var entries: List<MatchHistoryEntry>? = null
    private var error: String? = null

    /** Y of the first row, set during render so click hit-testing matches it
     *  (it shifts down when the trend chart is shown). */
    private var rowsTop = TOP

    /** First visible row index; driven by the mouse wheel. */
    private var scroll = 0

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
        list.filter { it.ratingAfter != null }
            .map { Ui.ChartPoint(it.ratingAfter!!, it.completedAt) }
            .reversed()

    private val opened = FirstInit()
    private val loaded = FirstInit()

    override fun init() {
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
            error = "Not signed in"
            return
        }
        // Once per screen, not once per resize — see PlayerProfileScreen.
        loaded.once {
            val minecraft = this.minecraft
            YabRankedClient.workers.execute {
                val fetched = backend.fetchHistory(profile.uuid, limit = 50)
                minecraft.execute {
                    when (fetched) {
                        is BackendClient.Fetch.Ok -> {
                            entries = fetched.value
                            RankedState.winStreak = RankedState.currentWinStreak(fetched.value)
                            if (fetched.value.isEmpty()) error = "Play a ranked match to see your history"
                        }
                        // "why" matters here: offline, expired session and an
                        // outdated mod each need a different move from the player.
                        is BackendClient.Fetch.Error -> error = fetched.message
                    }
                }
            }
        }
    }

    private fun resultColor(result: String) = when (result) {
        "win" -> Ui.WIN
        "loss" -> Ui.LOSS
        "draw" -> Ui.DRAW
        else -> Ui.TEXT_FAINT
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()

        val centerX = width / 2
        val left = centerX - WIDTH / 2
        Ui.header(g, centerX - 110, 10, 220, 24)
        g.centeredText(font, "§lMATCH HISTORY", centerX, 17, Ui.ACCENT)

        // magnifier in front of the search box — drawn on every state so it never
        // disappears while loading or on an error card.
        Ui.icon(g, Ui.ICON_SEARCH, left + 2, 50, 10, Ui.TEXT_DIM)

        val list = entries
        if (error != null) {
            Ui.messageCard(g, font, centerX, 74, error!!)
            Ui.fadeIn(g, width, height, openedAt)
            return
        }
        if (list == null) {
            drawLoadingSkeleton(g, centerX)
            Ui.fadeIn(g, width, height, openedAt)
            return
        }

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
            // expand affordance, tucked at the plot's top-right (clear of the axis)
            val hint = "⤢ expand"
            g.text(font, "§8$hint", left + WIDTH - 32 - font.width(hint) - 2, CHART_TOP + 1, Ui.TEXT_FAINT)
            rowsTop = CHART_TOP + CHART_HEIGHT + 6
        }

        val shown = filtered(list)
        if (shown.isEmpty()) {
            g.centeredText(font, "§7No matches against that opponent", centerX, rowsTop + 8, Ui.TEXT_DIM)
            Ui.fadeIn(g, width, height, openedAt)
            return
        }
        val visible = ((height - 40 - rowsTop) / ROW_HEIGHT).coerceAtLeast(1)
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
        Ui.scrollbar(g, left + WIDTH + 2, rowsTop, visible * ROW_HEIGHT, shown.size, visible, scroll)
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun ensureVisible() {
        val v = ((height - 40 - rowsTop) / ROW_HEIGHT).coerceAtLeast(1)
        if (selected < scroll) scroll = selected
        else if (selected >= scroll + v) scroll = selected - v + 1
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (search?.isFocused == true) return super.keyPressed(event)
        val shown = entries?.let { filtered(it) } ?: return super.keyPressed(event)
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
        val sk = 0xFF2A2A2A.toInt()
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
        if (hovered) g.fill(left + 2, y + 1, left + WIDTH - 2, y + ROW_HEIGHT - 2, 0x1AFFFFFF)
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

        // rating delta, or a dash for voided matches that never counted
        val delta = entry.ratingAfter?.let { after ->
            val diff = after - entry.ratingBefore
            if (diff >= 0) "§a+$diff" else "§c$diff"
        } ?: "§8—"
        Ui.textRight(g, font, delta, left + WIDTH - 62, textY, Ui.TEXT_DIM)
        // when the match was played, so the list reads as a timeline
        val ago = Ui.relativeTime(entry.completedAt)
        if (ago.isNotEmpty()) Ui.textRight(g, font, "§8$ago", left + WIDTH - 10, textY, Ui.TEXT_FAINT)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val shown = entries?.let { filtered(it) } ?: return false

        val left = width / 2 - WIDTH / 2
        val mouseX = event.x()
        val mouseY = event.y()
        if (mouseX < left || mouseX > left + WIDTH) return false

        // Click the trend chart to open the full-screen version.
        val searching = !search?.value?.trim().isNullOrEmpty()
        val points = entries?.let { chartPoints(it) }.orEmpty()
        if (!searching && points.size >= 2 &&
            mouseY >= CHART_TOP && mouseY < CHART_TOP + CHART_HEIGHT
        ) {
            Sfx.select()
            val name = RankedState.profile?.name ?: "Your"
            minecraft.setScreenAndShow(MmrChartScreen(this, points, "$name · last ${points.size} rated matches"))
            return true
        }

        val visible = ((height - 40 - rowsTop) / ROW_HEIGHT).coerceAtLeast(1)
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

    override fun mouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
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
        const val WIDTH = 280
        const val TOP = 68 // rows top when the chart is hidden (below the search box)
        const val ROW_HEIGHT = 14
        const val CHART_TOP = 68 // chart sits below the search box (y=48..62)
        const val CHART_HEIGHT = 48
    }
}
