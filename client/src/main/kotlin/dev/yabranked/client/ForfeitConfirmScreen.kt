package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Conceding is destructive and rating-affecting, so it is confirmed rather
 * than fired straight from the ranked menu.
 */
class ForfeitConfirmScreen(
    private val parent: Screen?,
    private val opponentName: String,
) : Screen(Component.literal("Forfeit Match")) {

    override fun init() {
        val centerX = width / 2
        addRenderableWidget(
            Button.builder(Component.literal("§cForfeit — $opponentName wins")) { forfeit() }
                .bounds(centerX - 100, height / 2 + 10, 200, 20)
                .build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Keep playing")) { onClose() }
                .bounds(centerX - 100, height / 2 + 34, 200, 20)
                .build()
        )
    }

    private fun forfeit() {
        // the agent owns the outcome; the server command is the single source
        // of truth so the client cannot fake a result
        minecraft.player?.connection?.sendCommand("forfeit")
        onClose()
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        val centerX = width / 2
        g.centeredText(font, "§lFORFEIT MATCH?", centerX, height / 2 - 40, Ui.LOSS)
        g.centeredText(font, "§7This counts as a loss and affects your rating.", centerX, height / 2 - 22, Ui.TEXT_DIM)
        g.centeredText(font, "§7$opponentName will be awarded the win.", centerX, height / 2 - 10, Ui.TEXT_DIM)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }
}
