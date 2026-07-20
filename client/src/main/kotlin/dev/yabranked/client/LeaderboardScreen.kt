package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class LeaderboardScreen(
    private val parent: Screen?,
) : Screen(Component.literal("Leaderboard")) {

    private var entries: List<WireProfile>? = null
    private var error: String? = null

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("Back")) { onClose() }
                .bounds(width / 2 - 100, height - 28, 200, 20)
                .build()
        )

        val backend = RankedState.backend
        if (backend == null) {
            error = "Not signed in"
            return
        }
        if (entries == null) {
            val minecraft = this.minecraft
            YabRankedClient.workers.execute {
                val fetched = backend.fetchLeaderboard(limit = 15)
                minecraft.execute {
                    entries = fetched
                    if (fetched.isEmpty()) error = "No rated players yet this season"
                }
            }
        }
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        Ui.header(g, centerX - 110, 10, 220, 24)
        g.centeredText(font, "§lLEADERBOARD", centerX, 17, Ui.ACCENT)

        val left = centerX - WIDTH / 2
        val list = entries

        if (list == null || error != null) {
            g.centeredText(font, error ?: "Loading…", centerX, 60, Ui.TEXT_DIM)
            return
        }

        // column headers
        g.text(font, "PLAYER", left + 34, TOP - 12, Ui.TEXT_FAINT)
        Ui.textRight(g, font, "W/L", left + WIDTH - 62, TOP - 12, Ui.TEXT_FAINT)
        Ui.textRight(g, font, "MMR", left + WIDTH - 10, TOP - 12, Ui.TEXT_FAINT)

        val self = RankedState.profile?.uuid
        list.forEachIndexed { index, profile ->
            val y = TOP + index * ROW_HEIGHT
            if (y + ROW_HEIGHT > height - 40) return@forEachIndexed
            drawRow(g, left, y, index + 1, profile, isSelf = profile.uuid == self)
        }
    }

    private fun drawRow(g: GuiGraphicsExtractor, left: Int, y: Int, position: Int, profile: WireProfile, isSelf: Boolean) {
        val tierColor = Ui.tierColor(profile.tier)

        // the viewer's own row is highlighted so it is findable at a glance
        Ui.row(g, left, y, WIDTH, ROW_HEIGHT - 1)
        if (isSelf) g.fill(left + 2, y + 1, left + WIDTH - 2, y + ROW_HEIGHT - 2, 0x33FFC93C)
        Ui.accentBar(g, left, y, ROW_HEIGHT - 1, tierColor)

        val textY = y + 4

        // podium positions get the accent colour
        val positionColor = if (position <= 3) Ui.ACCENT else Ui.TEXT_DIM
        g.text(font, "#$position", left + 8, textY, positionColor)

        PlayerHeads.draw(g, left + 30, y + 2, 8, profile.uuid, profile.name, tierColor)
        g.text(font, profile.name, left + 44, textY, if (isSelf) Ui.ACCENT else Ui.WHITE)
        Ui.rankBadge(g, left + 124, y - 1, profile.tier)
        g.text(font, profile.tier, left + 142, textY, tierColor)

        Ui.textRight(g, font, "§a${profile.wins}§7/§c${profile.losses}", left + WIDTH - 62, textY, Ui.TEXT_DIM)
        Ui.textRight(g, font, "${profile.rating}", left + WIDTH - 10, textY, Ui.WHITE)
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
