package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Grid of profile-card backgrounds; clicking one saves it as the player's card
 * art via the backend. Reached from [RankedOptionsScreen]. Mirrors
 * [CountryPickerScreen] but shows a live swatch of each background.
 */
class BackgroundPickerScreen(
    private val parent: Screen?,
) : Screen(Component.literal("Card Background")) {

    private var saving = false
    private var status: String? = null
    private var openedAt = 0L

    private val opened = FirstInit()

    override fun init() {
        val centerX = width / 2
        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        opened.once { Sfx.open() }
    }

    private fun choose(id: String) {
        val backend = RankedState.backend ?: run { status = "Not signed in"; return }
        if (saving) return
        saving = true
        status = "Saving ${Backgrounds.label(id)}…"
        val minecraft = this.minecraft
        YabRankedClient.workers.execute {
            val updated = backend.updateProfile(background = id)
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
        g.centeredText(font, "§lCARD BACKGROUND", centerX, 17, Ui.ACCENT)

        val current = RankedState.profile?.background ?: "default"
        status?.let { g.centeredText(font, "§7$it", centerX, height - 44, Ui.TEXT_DIM) }
            ?: g.centeredText(font, "§7Current: ${Backgrounds.label(current)}", centerX, height - 44, Ui.TEXT_DIM)

        val left = centerX - GRID_W / 2
        for ((i, id) in Backgrounds.ids.withIndex()) {
            val col = i % COLS
            val row = i / COLS
            val x = left + col * CELL_W
            val y = TOP + row * CELL_H
            val hovered = mouseX in x until (x + CELL_W - 6) && mouseY in y until (y + CELL_H - 6)
            drawSwatch(g, x, y, id, hovered, id == current)
        }
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun drawSwatch(g: GuiGraphicsExtractor, x: Int, y: Int, id: String, hovered: Boolean, selected: Boolean) {
        val w = CELL_W - 6
        val h = CELL_H - 6
        Ui.panel(g, x, y, w, h)
        Ui.drawUserBackground(g, x + 2, y + 2, w - 4, h - 18, id, alpha = if (hovered || selected) 0x40 else 0x66)
        g.centeredText(font, Ui.fit(font, Backgrounds.label(id), w - 4), x + w / 2, y + h - 10, if (selected) Ui.ACCENT else Ui.WHITE)
        val edge = when {
            selected -> Ui.ACCENT
            hovered -> Ui.alpha(Ui.WHITE, 0x66)
            else -> 0
        }
        if (edge != 0) {
            g.fill(x, y, x + w, y + 1, edge)
            g.fill(x, y + h - 1, x + w, y + h, edge)
            g.fill(x, y, x + 1, y + h, edge)
            g.fill(x + w - 1, y, x + w, y + h, edge)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val left = width / 2 - GRID_W / 2
        for ((i, id) in Backgrounds.ids.withIndex()) {
            val x = left + (i % COLS) * CELL_W
            val y = TOP + (i / COLS) * CELL_H
            if (event.x() >= x && event.x() < x + CELL_W - 6 && event.y() >= y && event.y() < y + CELL_H - 6) {
                // The swatches are not widgets, so they owe the click the sound
                // a RankedButton would have played; the save round-trip that
                // ends in Sfx.success is a second or two away.
                Sfx.select()
                choose(id)
                return true
            }
        }
        return false
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val COLS = 5
        const val CELL_W = 76
        const val CELL_H = 60
        const val GRID_W = COLS * CELL_W
        const val TOP = 44
    }
}
