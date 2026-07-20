package dev.yabranked.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.time.Duration

private const val WHITE = -1

class MatchHistoryScreen(
    private val parent: Screen,
) : Screen(Component.literal("Match History")) {

    private var entries: List<WireHistoryEntry>? = null
    private var error: String? = null

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build()
        )

        val backend = RankedState.backend
        val profile = RankedState.profile
        if (backend == null || profile == null) {
            error = "Not logged in"
            return
        }
        if (entries == null) {
            val minecraft = this.minecraft
            YabRankedClient.workers.execute {
                val fetched = backend.fetchHistory(profile.uuid, limit = 12)
                minecraft.execute {
                    entries = fetched
                    if (fetched.isEmpty()) error = "No matches this season"
                }
            }
        }
    }

    private fun formatEntry(entry: WireHistoryEntry): String {
        val result = when (entry.result) {
            "win" -> "§aWin"
            "loss" -> "§cLoss"
            "draw" -> "§7Draw"
            else -> "§8Void"
        }
        val delta = entry.ratingAfter?.let { after ->
            val diff = after - entry.ratingBefore
            if (diff >= 0) "§a+$diff" else "§c$diff"
        } ?: "§8—"
        val duration = entry.durationSeconds?.let {
            val d = Duration.ofSeconds(it)
            "%d:%02d".format(d.toMinutes(), d.toSecondsPart())
        } ?: "?"
        return "$result§r vs §e${entry.opponent.name}§r  $delta§r  §7$duration"
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val centerX = width / 2
        extractor.centeredText(font, title, centerX, 16, WHITE)

        val list = entries
        when {
            error != null ->
                extractor.centeredText(font, "§7$error", centerX, 40, WHITE)
            list == null ->
                extractor.centeredText(font, "§7Loading...", centerX, 40, WHITE)
            else -> list.forEachIndexed { index, entry ->
                val y = 36 + index * 12
                if (y > height - 40) return@forEachIndexed
                extractor.centeredText(font, formatEntry(entry), centerX, y, WHITE)
            }
        }
    }

    override fun onClose() {
        minecraft.setScreenAndShow(parent)
    }
}
