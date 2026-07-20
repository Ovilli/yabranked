package dev.yabranked.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

private const val WHITE = -1

class LeaderboardScreen(
    private val parent: Screen,
) : Screen(Component.literal("Ranked Leaderboard")) {

    private var entries: List<WireProfile>? = null
    private var error: String? = null

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("Done")) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build()
        )

        val backend = RankedState.backend
        if (backend == null) {
            error = "Not logged in"
            return
        }
        if (entries == null) {
            val minecraft = this.minecraft
            YabRankedClient.workers.execute {
                val fetched = backend.fetchLeaderboard(limit = 15)
                minecraft.execute {
                    entries = fetched
                    if (fetched.isEmpty()) error = "No rated players yet"
                }
            }
        }
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
            else -> {
                val self = RankedState.profile?.uuid
                list.forEachIndexed { index, profile ->
                    val y = 36 + index * 12
                    if (y > height - 40) return@forEachIndexed
                    val highlight = if (profile.uuid == self) "§e" else "§f"
                    val line = "$highlight#${index + 1}  ${profile.name} — ${profile.rating}" +
                        "  §7(${profile.wins}W ${profile.losses}L)"
                    extractor.centeredText(font, line, centerX, y, WHITE)
                }
            }
        }
    }

    override fun onClose() {
        minecraft.setScreenAndShow(parent)
    }
}
