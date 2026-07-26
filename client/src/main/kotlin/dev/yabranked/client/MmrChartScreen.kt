package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Full-screen MMR history chart — the "expand" view opened from the small chart
 * on [MatchHistoryScreen]. Same [Ui.eloChart] renderer, just given most of the
 * screen so the trend, gridlines and dated ticks are actually readable.
 */
class MmrChartScreen(
    private val parent: Screen?,
    private val points: List<Ui.ChartPoint>,
    private val subtitle: String,
) : Screen(Component.literal("MMR History")) {

    private var openedAt = 0L

    private val opened = FirstInit()

    override fun init() {
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        opened.once { Sfx.open() }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()

        val centerX = width / 2
        Ui.header(g, centerX - 120, 10, 240, 24)
        g.centeredText(font, "§lMMR HISTORY", centerX, 17, Ui.ACCENT)
        g.centeredText(font, "§7$subtitle", centerX, 38, Ui.TEXT_DIM)

        // Chart takes the middle band, capped so it stays a sensible shape on very
        // wide/tall windows.
        val marginX = maxOf(24, (width - 520) / 2)
        val chartX = marginX
        val chartW = width - marginX * 2
        val chartTop = 54
        val chartH = (height - chartTop - 64).coerceIn(90, 260)

        Ui.panel(g, chartX - 2, chartTop - 2, chartW + 4, chartH + 4)
        Ui.eloChart(g, font, chartX, chartTop, chartW, chartH, points, mouseX, mouseY)

        // Summary strip below the chart.
        val ratings = points.map { it.rating }
        if (ratings.isNotEmpty()) {
            val peak = ratings.max()
            val low = ratings.min()
            val net = ratings.last() - ratings.first()
            val netTxt = if (net >= 0) "§a+$net" else "§c$net"
            val line = "§7Peak §f$peak   §7Low §f$low   §7Net $netTxt§7   Points §f${ratings.size}"
            g.centeredText(font, line, centerX, chartTop + chartH + 12, Ui.TEXT_DIM)
        }

        Ui.fadeIn(g, width, height, openedAt)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }
}
