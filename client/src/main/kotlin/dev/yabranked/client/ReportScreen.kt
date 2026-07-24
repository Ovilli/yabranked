package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Player report flow, modelled on MCSR Ranked: rather than a single fire-and-
 * forget button, the reporter picks a reason category for a specific opponent.
 * The reason is sent verbatim to the backend, which pins the accused to the
 * given match, so no player identity is trusted from the client.
 */
class ReportScreen(
    private val parent: Screen?,
    private val matchId: String,
    private val opponentName: String,
) : Screen(Component.literal("Report Player")) {

    private var submitting = false
    private var buttons = mutableListOf<RankedButton>()

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    override fun init() {
        buttons.clear()
        val centerX = width / 2

        // Centre the reason stack so the screen reads the same at any GUI scale.
        val rows = REASONS.size + 1 // reasons + cancel
        var by = height / 2 - (rows * ROW) / 2

        for ((label, reason) in REASONS) {
            val b = RankedButton(centerX - 100, by, 200, 20, Component.literal(label)) { submit(reason) }
            buttons += addRenderableWidget(b)
            by += ROW
        }

        by += 4 // small gap before the escape hatch
        addRenderableWidget(
            RankedButton(centerX - 100, by, 200, 20, Component.literal("Cancel"), Ui.ICON_BACK) { onClose() }
        )
    }

    private fun submit(reason: String) {
        val backend = RankedState.backend
        if (backend == null || submitting) return
        submitting = true
        buttons.forEach { it.active = false }

        val minecraft = this.minecraft
        YabRankedClient.workers.execute {
            val status = backend.submitReport(matchId, reason)
            minecraft.execute {
                RankedState.lastMatchReported = true
                RankedToast.showInfo("Report submitted", "We will review it soon.")
                RankedState.statusMessage = status
                onClose()
            }
        }
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        val centerX = width / 2
        val top = height / 2 - (REASONS.size + 1) * ROW / 2

        g.centeredText(font, "§lREPORT PLAYER", centerX, top - 44, Ui.LOSS)
        g.centeredText(font, "Reporting $opponentName", centerX, top - 28, Ui.TEXT_DIM)
        g.centeredText(font, "§7Pick the reason that best fits.", centerX, top - 16, Ui.TEXT_FAINT)

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val ROW = 24

        /** Label shown on the button paired with the reason sent to the backend. */
        val REASONS = listOf(
            "Toxic Chat" to "toxic_chat",
            "Hacking" to "hacking",
            "Smurfing" to "smurfing",
            "Queue Dodging" to "queue_dodging",
            "Other" to "other",
        )
    }
}
