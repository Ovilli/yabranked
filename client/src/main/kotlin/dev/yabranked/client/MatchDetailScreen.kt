package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Full breakdown of a single match, opened from a [MatchHistoryScreen] row: the
 * result, MMR swing, objective score, duration, forfeit state and the world /
 * card seeds. Everything comes from the [MatchHistoryEntry] already fetched, so
 * there is no extra request.
 */
class MatchDetailScreen(
    private val parent: Screen?,
    private val entry: MatchHistoryEntry,
) : ScaledScreen(Component.literal("Match Details")) {

    private var openedAt = 0L

    private val opened = FirstInit()

    override fun layout() {
        val centerX = width / 2
        addRenderableWidget(
            RankedButton(centerX - 122, height - 28, 120, 20, Component.literal("Opponent"), Ui.ICON_LEADERBOARD) {
                Sfx.select()
                minecraft.setScreenAndShow(PlayerProfileScreen(this, entry.opponent.uuid, entry.opponent.name))
            }
        )
        addRenderableWidget(
            RankedButton(centerX + 2, height - 28, 120, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        // Offered unconditionally: whether a recording exists is the backend's
        // answer, and the viewer says so plainly. Hiding the button on a guess
        // would mean no way in whenever that guess was wrong.
        addRenderableWidget(
            RankedButton(centerX - 122, height - 52, 244, 20, Component.literal("Watch replay"), Ui.ICON_BOARD) {
                Sfx.select()
                minecraft.setScreenAndShow(
                    dev.yabranked.client.replay.ReplayDownloadScreen(this, entry.matchId, "vs ${entry.opponent.name} · ${entry.format.displayName}")
                )
            }
        )
        opened.once { Sfx.open() }
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()

        val centerX = width / 2
        Ui.header(g, centerX - 110, 10, 220, 24)
        g.centeredText(font, "§lMATCH DETAILS", centerX, 17, Ui.ACCENT)

        val left = centerX - CARD_W / 2
        Ui.panel(g, left, TOP, CARD_W, CARD_H)

        // Result banner.
        val (resultText, resultColor) = when (entry.result) {
            "win" -> "VICTORY" to Ui.WIN
            "loss" -> "DEFEAT" to Ui.LOSS
            "draw" -> "DRAW" to Ui.DRAW
            else -> "VOIDED" to Ui.TEXT_FAINT
        }
        Ui.accentBar(g, left, TOP, CARD_H, resultColor)
        g.text(font, "§l$resultText", left + 10, TOP + 10, resultColor)
        val ago = Ui.relativeTime(entry.completedAt)
        if (ago.isNotEmpty()) Ui.textRight(g, font, "§8$ago", left + CARD_W - 10, TOP + 11, Ui.TEXT_FAINT)

        // Opponent line.
        val oy = TOP + 26
        Ui.slot(g, left + 8, oy, 18)
        PlayerHeads.draw(g, left + 10, oy + 2, 14, entry.opponent.uuid, entry.opponent.name, Ui.WHITE)
        var nx = left + 32
        if (RankedState.showFlags) {
            entry.opponent.country?.let { Ui.flagIcon(g, nx, oy + 3, it, 9); nx += 12 }
        }
        // Stops short of the MMR column on the same line.
        g.text(font, Ui.fit(font, "§7vs §f${entry.opponent.name}", left + CARD_W - 64 - nx), nx, oy + 3, Ui.WHITE)
        entry.opponentRating?.let {
            Ui.textRight(g, font, "§7$it MMR", left + CARD_W - 10, oy + 3, Ui.TEXT_DIM)
        }

        // Stat rows.
        var ry = TOP + 48
        row(g, left, ry, "Mode", "§f${entry.format.displayName}" + if (entry.rated) "" else " §8(casual)"); ry += ROW
        val mmrLine = when {
            // Unrated modes still carry the rating the player had at the time;
            // rendering it as a before → after pair implies a swing that never
            // happened.
            !entry.rated -> "§8— casual, unrated —"
            entry.ratingAfter == null -> "§8— did not count —"
            else -> {
                val after = entry.ratingAfter!!
                val d = after - entry.ratingBefore
                val dtxt = if (d >= 0) "§a+$d" else "§c$d"
                "${entry.ratingBefore} → §f$after  $dtxt"
            }
        }
        row(g, left, ry, "MMR", mmrLine); ry += ROW
        if (entry.yourScore != null || entry.opponentScore != null) {
            row(g, left, ry, "Score", "§f${entry.yourScore ?: 0} §7— §f${entry.opponentScore ?: 0} §8items"); ry += ROW
        }
        row(g, left, ry, "Duration", Ui.duration(entry.durationSeconds)); ry += ROW
        if (entry.wasForfeit) {
            val who = if (entry.forfeitedByYou) "§cYou forfeited" else "§aOpponent forfeited"
            row(g, left, ry, "Forfeit", who); ry += ROW
        }
        entry.worldSeed?.let { row(g, left, ry, "World seed", "§7$it"); ry += ROW }
        entry.cardSeed?.let { row(g, left, ry, "Card seed", "§7$it"); ry += ROW }

        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun row(g: GuiGraphicsExtractor, left: Int, y: Int, label: String, value: String) {
        g.text(font, "§8$label", left + 10, y, Ui.TEXT_FAINT)
        g.text(font, value, left + 74, y, Ui.WHITE)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val CARD_W = 240
        const val CARD_H = 150
        const val TOP = 42
        const val ROW = 13
    }
}
