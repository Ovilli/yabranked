package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Searchable grid of country flags. Clicking one sends it to the backend as the
 * player's flag; "No flag" clears it. Reached from [RankedOptionsScreen].
 */
class CountryPickerScreen(
    private val parent: Screen?,
) : Screen(Component.literal("Set Country")) {

    private var search: EditBox? = null
    private var scroll = 0
    private var saving = false
    private var status: String? = null

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    private fun matches(): List<String> = CountryData.search(search?.value.orEmpty())

    private val opened = FirstInit()

    override fun init() {
        val centerX = width / 2
        val left = centerX - WIDTH / 2

        search = addRenderableWidget(
            EditBox(font, left + 14, 36, WIDTH - 14 - 96, 16, Component.literal("Search")).apply {
                setHint(Component.literal("Search countries…"))
                setMaxLength(32)
                value = search?.value ?: ""
                setResponder { scroll = 0 }
            }
        )
        addRenderableWidget(
            RankedButton(left + WIDTH - 92, 36, 92, 16, Component.literal("No flag")) { choose(null) }
        )
        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        opened.once { Sfx.open() }
    }

    private fun choose(code: String?) {
        val backend = RankedState.backend ?: run { status = "Not signed in"; return }
        if (saving) return
        saving = true
        status = if (code == null) "Clearing…" else "Saving ${CountryData.name(code)}…"
        val minecraft = this.minecraft
        YabRankedClient.workers.execute {
            // "" tells the backend to clear; a code sets it.
            val updated = backend.updateProfile(country = code ?: "")
            minecraft.execute {
                saving = false
                if (updated != null) {
                    RankedState.profile = updated
                    Sfx.success()
                    onClose()
                } else {
                    status = "Could not save — try again"
                }
            }
        }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()

        val centerX = width / 2
        Ui.header(g, centerX - 110, 10, 220, 24)
        g.centeredText(font, "§lSET COUNTRY", centerX, 17, Ui.ACCENT)

        val left = centerX - WIDTH / 2
        Ui.icon(g, Ui.ICON_SEARCH, left + 2, 38, 10, Ui.TEXT_DIM)

        val current = RankedState.profile?.country
        status?.let { g.centeredText(font, "§7$it", centerX, height - 44, Ui.TEXT_DIM) }
            ?: current?.let {
                g.centeredText(font, "§7Current: ${CountryData.name(it)}", centerX, height - 44, Ui.TEXT_DIM)
            }

        val list = matches()
        if (list.isEmpty()) {
            g.centeredText(font, "§7No countries match your search", centerX, TOP + 12, Ui.TEXT_DIM)
            Ui.fadeIn(g, width, height, openedAt)
            return
        }

        val rows = (list.size + COLS - 1) / COLS
        val visibleRows = ((height - 40 - TOP) / CELL_H).coerceAtLeast(1)
        scroll = scroll.coerceIn(0, (rows - visibleRows).coerceAtLeast(0))

        for (r in 0 until visibleRows) {
            val row = r + scroll
            if (row >= rows) break
            for (c in 0 until COLS) {
                val idx = row * COLS + c
                if (idx >= list.size) break
                val code = list[idx]
                val x = left + c * CELL_W
                val y = TOP + r * CELL_H
                val hovered = mouseX in x until (x + CELL_W - 2) && mouseY in y until (y + CELL_H - 2)
                drawCell(g, x, y, code, hovered, code == current)
            }
        }
        Ui.scrollbar(g, left + WIDTH + 2, TOP, visibleRows * CELL_H, rows, visibleRows, scroll)
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun drawCell(g: GuiGraphicsExtractor, x: Int, y: Int, code: String, hovered: Boolean, selected: Boolean) {
        val w = CELL_W - 2
        val h = CELL_H - 2
        Ui.row(g, x, y, w, h)
        if (selected) g.fill(x, y, x + w, y + h, Ui.SELECTION)
        if (hovered) g.fill(x, y, x + w, y + h, Ui.HOVER)
        if (selected || hovered) {
            val edge = if (selected) Ui.ACCENT else Ui.alpha(Ui.WHITE, 0x55)
            g.fill(x, y, x + w, y + 1, edge)
            g.fill(x, y + h - 1, x + w, y + h, edge)
            g.fill(x, y, x + 1, y + h, edge)
            g.fill(x + w - 1, y, x + w, y + h, edge)
        }

        val flagW = 24
        Ui.flagIcon(g, x + 4, y + 4, code, flagW)
        val label = Ui.fit(font, CountryData.name(code), w - flagW - 12)
        g.text(font, label, x + flagW + 8, y + (h - 8) / 2, if (selected) Ui.ACCENT else Ui.WHITE)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val list = matches()
        if (list.isEmpty()) return false

        val left = width / 2 - WIDTH / 2
        val mx = event.x()
        val my = event.y()
        val visibleRows = ((height - 40 - TOP) / CELL_H).coerceAtLeast(1)
        for (r in 0 until visibleRows) {
            val row = r + scroll
            for (c in 0 until COLS) {
                val idx = row * COLS + c
                if (idx >= list.size) continue
                val x = left + c * CELL_W
                val y = TOP + r * CELL_H
                if (mx >= x && mx < x + CELL_W - 2 && my >= y && my < y + CELL_H - 2) {
                    choose(list[idx])
                    return true
                }
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
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val WIDTH = 300
        const val COLS = 3
        const val CELL_W = WIDTH / COLS
        const val CELL_H = 20
        const val TOP = 60
    }
}
