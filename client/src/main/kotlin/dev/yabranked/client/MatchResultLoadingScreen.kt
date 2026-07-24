package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component

/**
 * Placeholder shown the instant a ranked match ends, while the backend result
 * is fetched. Without it the player briefly sees vanilla's disconnect / server
 * list screen (the "join" screen) before [MatchResultScreen] appears, because
 * the result poll takes a second or two. This claims the screen right away and
 * is swapped out by the disconnect handler once the result lands.
 */
class MatchResultLoadingScreen(
    private val opponentName: String,
) : Screen(Component.literal("Match Result")) {

    private var ticks = 0

    override fun init() {
        RankedState.onResultLoading = true
        // Escape hatch in case the result never arrives (server hiccup).
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 40, 200, 20, Component.literal("Skip")) { onClose() }
        )
    }

    override fun tick() {
        ticks++
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        val centerX = width / 2

        g.centeredText(font, "§lMATCH COMPLETE", centerX, height / 2 - 26, Ui.ACCENT)
        g.centeredText(font, "§7vs $opponentName", centerX, height / 2 - 10, Ui.TEXT_DIM)

        val dots = ".".repeat(((ticks / 8) % 4))
        g.centeredText(font, "Calculating result$dots", centerX, height / 2 + 8, Ui.WHITE)
    }

    override fun shouldCloseOnEsc(): Boolean = false

    override fun onClose() {
        RankedState.onResultLoading = false
        minecraft.setScreenAndShow(TitleScreen())
    }
}
