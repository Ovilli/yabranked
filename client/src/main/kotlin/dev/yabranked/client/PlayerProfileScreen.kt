package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Read-only view of another player's profile, opened by clicking a leaderboard
 * row. Reuses the ranked card layout but sources everything from the fetched
 * profile rather than the local session.
 */
class PlayerProfileScreen(
    private val parent: Screen?,
    private val uuid: String,
    private val name: String,
) : Screen(Component.literal("Player Profile")) {

    private var profile: WireProfile? = null
    private var points: List<Ui.ChartPoint> = emptyList()
    private var history: List<WireHistoryEntry> = emptyList()
    private var versus: WireVersusRecord? = null
    private var error: String? = null

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    override fun init() {
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        Sfx.open()

        val backend = RankedState.backend
        if (backend == null) {
            error = "Not signed in"
            return
        }
        if (profile == null) {
            val minecraft = this.minecraft
            val self = RankedState.profile
            YabRankedClient.workers.execute {
                val fetched = backend.fetchProfile(uuid)
                val hist = backend.fetchHistory(uuid, limit = 20)
                // Head-to-head only makes sense against someone other than you.
                val h2h = if (self != null && self.uuid != uuid) {
                    backend.fetchVersus(self.uuid, uuid)
                } else null
                minecraft.execute {
                    profile = fetched
                    history = hist
                    points = hist.filter { it.ratingAfter != null }
                        .map { Ui.ChartPoint(it.ratingAfter!!, it.completedAt) }
                        .reversed()
                    versus = h2h
                    if (fetched == null) error = "Could not load $name"
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
        g.centeredText(font, "§lPLAYER PROFILE", centerX, 17, Ui.ACCENT)

        val p = profile
        if (p == null) {
            Ui.messageCard(g, font, centerX, 66, error ?: "Loading…")
            Ui.fadeIn(g, width, height, openedAt)
            return
        }

        drawCard(g, centerX, p)

        var belowY = CARD_TOP + CARD_HEIGHT + 8
        if (points.size >= 2) {
            Ui.eloChart(g, font, centerX - CARD_WIDTH / 2, belowY, CARD_WIDTH, 50, points, mouseX, mouseY)
            belowY += 58
        }

        versus?.let { v ->
            val line = if (v.played == 0) {
                "No matches against you yet"
            } else {
                "Head-to-head — you ${v.wins}W · ${v.losses}L" +
                    if (v.draws > 0) " · ${v.draws}D" else ""
            }
            g.centeredText(font, line, centerX, belowY + 2, Ui.TEXT_DIM)
            belowY += 14
        }

        if (history.isNotEmpty()) drawDeepDive(g, centerX, belowY + 4, p)

        Ui.fadeIn(g, width, height, openedAt)
    }

    /**
     * Recent-form dots plus a career stat grid — the numbers MCSR Ranked surfaces
     * on a profile that our raw card does not: peak MMR, best/current streak,
     * season games, best single gain and average game length.
     */
    private fun drawDeepDive(g: GuiGraphicsExtractor, centerX: Int, y: Int, p: WireProfile) {
        val left = centerX - CARD_WIDTH / 2

        // Form: last 10 matches, oldest→newest, as coloured dots.
        val recent = history.take(10).reversed()
        g.text(font, "§7Recent form", left, y, Ui.TEXT_FAINT)
        var dx = left + font.width("Recent form ") + 4
        for (e in recent) {
            g.fill(dx, y - 1, dx + 6, y + 6, resultColor(e.result))
            dx += 9
        }

        // Career figures derived from the fetched history window + season totals.
        val peak = history.mapNotNull { it.ratingAfter }.maxOrNull()
        val bestStreak = longestWinRun()
        val streak = currentStreak()
        val games = p.wins + p.losses + p.draws
        val bestGain = history.mapNotNull { e -> e.ratingAfter?.let { it - e.ratingBefore } }.maxOrNull() ?: 0
        val avgDur = history.mapNotNull { it.durationSeconds }.let { if (it.isEmpty()) null else it.average().toLong() }

        val stats = listOf(
            "Peak MMR" to (peak?.let { if (RankedState.hideElo && p.uuid == RankedState.profile?.uuid) "—" else it.toString() } ?: "—"),
            "Season games" to games.toString(),
            "Best streak" to (if (bestStreak > 0) "§a${bestStreak}W" else "—"),
            "Current form" to streakText(streak),
            "Best gain" to (if (bestGain > 0) "§a+$bestGain" else "—"),
            "Avg game" to (avgDur?.let { Ui.duration(it) } ?: "—"),
            "Playtime" to Ui.durationLong(p.playtimeSeconds),
            "Forfeits" to (if (p.forfeits > 0) "§c${p.forfeits}" else "0"),
        )

        val gridY = y + 18
        g.text(font, "§7CAREER", left, gridY - 12, Ui.TEXT_FAINT)
        val colW = CARD_WIDTH / 2
        for ((i, s) in stats.withIndex()) {
            val col = i % 2
            val row = i / 2
            val cx = left + col * colW
            val cy = gridY + row * 12
            g.text(font, "§7${s.first}", cx, cy, Ui.TEXT_FAINT)
            Ui.textRight(g, font, s.second, cx + colW - 10, cy, Ui.WHITE)
        }
    }

    private fun resultColor(result: String) = when (result) {
        "win" -> Ui.WIN
        "loss" -> Ui.LOSS
        "draw" -> Ui.DRAW
        else -> Ui.TEXT_FAINT
    }

    /** Longest run of consecutive wins anywhere in the fetched window. */
    private fun longestWinRun(): Int {
        var best = 0
        var run = 0
        for (e in history) {
            if (e.result == "win") { run++; if (run > best) best = run } else run = 0
        }
        return best
    }

    private fun streakText(streak: Int): String = when {
        streak > 0 -> "§a${streak}W ↑"
        streak < 0 -> "§c${-streak}L ↓"
        else -> "—"
    }

    /** Signed streak from the newest match: + for wins, − for losses, 0 mixed. */
    private fun currentStreak(): Int {
        var s = 0
        for (e in history) {
            when (e.result) {
                "win" -> if (s >= 0) s++ else return s
                "loss" -> if (s <= 0) s-- else return s
                else -> return s
            }
        }
        return s
    }

    private fun drawCard(g: GuiGraphicsExtractor, centerX: Int, p: WireProfile) {
        val left = centerX - CARD_WIDTH / 2
        val right = left + CARD_WIDTH
        val tierColor = Ui.tierColor(p.tier)

        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)
        Ui.drawUserBackground(g, left + 3, CARD_TOP + 3, CARD_WIDTH - 6, CARD_HEIGHT - 6, p.background)
        Ui.accentBar(g, left, CARD_TOP, CARD_HEIGHT, tierColor)

        val padLeft = left + 10
        val padRight = right - 10

        Ui.slot(g, padLeft - 2, CARD_TOP + 8, 28)
        PlayerHeads.draw(g, padLeft, CARD_TOP + 10, 24, p.uuid, p.name, tierColor)
        Ui.slot(g, padRight - 30, CARD_TOP + 8, 30)
        Ui.rankBadge(g, padRight - 27, CARD_TOP + 11, p.tier, size = 24)

        val textLeft = padLeft + 32
        var nameX = textLeft
        if (RankedState.showFlags) {
            p.country?.let {
                Ui.flagIcon(g, nameX, CARD_TOP + 9, it, 9)
                nameX += 12
            }
        }
        g.text(font, p.name, nameX, CARD_TOP + 8, Ui.WHITE)
        g.text(font, p.tier, textLeft, CARD_TOP + 20, tierColor)

        val ratingLine = p.rank?.let { "${p.rating} MMR · Rank #$it" } ?: "${p.rating} MMR"
        g.text(font, ratingLine, textLeft, CARD_TOP + 32, Ui.TEXT_DIM)
        g.text(font, "Season ${p.season}", textLeft, CARD_TOP + 44, Ui.TEXT_FAINT)

        val record = "${p.wins}W · ${p.losses}L" +
            if (p.draws > 0) " · ${p.draws}D" else ""
        g.text(font, record, padLeft, CARD_TOP + 62, Ui.TEXT_DIM)
        if (p.wins + p.losses > 0) {
            Ui.textRight(g, font, "${Ui.winRatePercent(p)}%", padRight, CARD_TOP + 62, Ui.TEXT_FAINT)
        }
        Ui.winRateBar(g, padLeft, CARD_TOP + 74, CARD_WIDTH - 20, p.wins, p.losses, p.draws)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val CARD_WIDTH = 220
        const val CARD_HEIGHT = 84
        const val CARD_TOP = 40
    }
}
