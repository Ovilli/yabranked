package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class MatchHistoryScreen(
    private val parent: Screen?,
) : Screen(Component.literal("Match History")) {

    private var entries: List<WireHistoryEntry>? = null
    private var error: String? = null

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("Back")) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build()
        )

        val backend = RankedState.backend
        val profile = RankedState.profile
        if (backend == null || profile == null) {
            error = "Not signed in"
            return
        }
        if (entries == null) {
            val minecraft = this.minecraft
            YabRankedClient.workers.execute {
                val fetched = backend.fetchHistory(profile.uuid, limit = 12)
                minecraft.execute {
                    entries = fetched
                    if (fetched.isEmpty()) error = "No matches played this season"
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

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        Ui.header(g, centerX - 110, 10, 220, 24)
        g.centeredText(font, "§lMATCH HISTORY", centerX, 17, Ui.ACCENT)

        val list = entries
        if (list == null || error != null) {
            g.centeredText(font, error ?: "Loading…", centerX, 60, Ui.TEXT_DIM)
            return
        }

        // record summary across the fetched window
        val wins = list.count { it.result == "win" }
        val losses = list.count { it.result == "loss" }
        g.centeredText(font, "§a${wins}W §7· §c${losses}L §7in the last ${list.size}", centerX, 31, Ui.TEXT_DIM)

        val left = centerX - WIDTH / 2
        list.forEachIndexed { index, entry ->
            val y = TOP + index * ROW_HEIGHT
            if (y + ROW_HEIGHT > height - 40) return@forEachIndexed
            drawRow(g, left, y, entry)
        }
    }

    private fun drawRow(g: GuiGraphicsExtractor, left: Int, y: Int, entry: WireHistoryEntry) {
        val color = resultColor(entry.result)

        Ui.row(g, left, y, WIDTH, ROW_HEIGHT - 1)
        Ui.accentBar(g, left, y, ROW_HEIGHT - 1, color)

        val textY = y + 4
        val label = when (entry.result) {
            "win" -> "WIN"
            "loss" -> "LOSS"
            "draw" -> "DRAW"
            else -> "VOID"
        }
        g.text(font, label, left + 8, textY, color)
        g.text(font, "vs ${entry.opponent.name}", left + 44, textY, Ui.WHITE)

        // rating delta, or a dash for voided matches that never counted
        val delta = entry.ratingAfter?.let { after ->
            val diff = after - entry.ratingBefore
            if (diff >= 0) "§a+$diff" else "§c$diff"
        } ?: "§8—"
        Ui.textRight(g, font, delta, left + WIDTH - 58, textY, Ui.TEXT_DIM)
        Ui.textRight(g, font, Ui.duration(entry.durationSeconds), left + WIDTH - 10, textY, Ui.TEXT_DIM)
    }

    override fun onClose() {
        // null parent means the screen was opened by keybind mid-game:
        // fall back to vanilla behaviour, which returns to the game
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val WIDTH = 280
        const val TOP = 46
        const val ROW_HEIGHT = 14
    }
}
