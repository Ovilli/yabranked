package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
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
) : ScaledScreen(Component.literal("Player Profile")) {

    private var profile: Loadable<PlayerProfile> = Loadable.Loading

    /** The way back from a failed read; see [dev.yabranked.client.ui.RetryCard]. */
    private val retry = dev.yabranked.client.ui.RetryCard { load() }
    private var points: List<Ui.ChartPoint> = emptyList()
    private var history: List<MatchHistoryEntry> = emptyList()
    private var versus: VersusRecord? = null
    private var achievements: List<Achievement> = emptyList()

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    /** Y of the trend chart's top, set during render; -1 when no chart is shown. */
    private var chartTop = -1

    private val opened = FirstInit()
    private val loaded = FirstInit()

    override fun layout() {
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        // Reporting is reachable from wherever a player is visible, not only
        // from the screen that follows their last match against you. Never
        // offered on your own profile — the backend would refuse it anyway,
        // since it resolves the accused out of a match you both played.
        if (uuid != RankedState.profile?.uuid) {
            addRenderableWidget(
                RankedButton(
                    width / 2 - 100, height - 50, 96, 18,
                    Component.literal("§cReport"), Ui.ICON_REPORT,
                ) {
                    Sfx.select()
                    minecraft.setScreenAndShow(ReportScreen(this, matchId = null, opponentName = name, opponentUuid = uuid))
                }
            ).tip("Report $name for misconduct in your most recent match together")
        }
        opened.once { Sfx.open() }

        val backend = RankedState.backend
        if (backend == null) {
            profile = Loadable.Failed("Not signed in")
            return
        }
        // Load once per screen, not once per resize: init() re-runs on every
        // frame of a window drag, and a failed load left profile null, so the
        // old `profile == null` guard retried the whole fetch each time.
        loaded.once { load() }
    }

    /**
     * Fetch the profile and everything drawn under it. Split out of [layout] so
     * a failed read has something for its Retry button to call — the
     * once-per-screen guard stays in [layout], since a retry is a deliberate
     * second attempt and a resize is not.
     */
    private fun load() {
        val backend = RankedState.backend ?: return
        val minecraft = this.minecraft
        val self = RankedState.profile
        profile = Loadable.Loading
        YabRankedClient.workers.execute {
            val fetched = backend.fetchProfile(uuid)
            val hist = backend.fetchHistory(uuid, limit = 20).orElse(emptyList())
            val achs = backend.fetchAchievements(uuid)
            // Head-to-head only makes sense against someone other than you.
            val h2h = if (self != null && self.uuid != uuid) {
                backend.fetchVersus(self.uuid, uuid)
            } else null
            minecraft.execute {
                history = hist
                achievements = achs
                // Casual matches carry the rating they were played at but
                // never moved it; charting them would draw flat runs that
                // look like the ladder stalled.
                points = hist.filter { it.rated && it.ratingAfter != null }
                    .map { Ui.ChartPoint(it.ratingAfter!!, it.completedAt) }
                    .reversed()
                versus = h2h
                profile = fetched.toLoadable()
            }
        }
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        val centerX = width / 2

        Ui.title(g, font, centerX, "§lPLAYER PROFILE")

        val p = when (val state = profile) {
            is Loadable.Loaded -> state.value
            is Loadable.Pending -> {
                retry.draw(
                    g, font, centerX, 66, state.message,
                    retryable = state is Loadable.Failed,
                    mouseX = mouseX, mouseY = mouseY,
                )
                Ui.fadeIn(g, width, height, openedAt)
                return
            }
        }

        drawCard(g, centerX, p)

        var belowY = CARD_TOP + CARD_HEIGHT + 8
        if (points.size >= 2) {
            Ui.eloChart(g, font, centerX - CARD_WIDTH / 2, belowY, CARD_WIDTH, 44, points, mouseX, mouseY)
            // Plain words, not a glyph: U+2922 is outside the default font's
            // coverage and rendered as a missing-character box, so the one cue
            // that the chart opens into a zoomable view read as an artefact.
            val hint = "click to zoom"
            g.text(font, "§8$hint", centerX + CARD_WIDTH / 2 - 32 - font.width(hint) - 2, belowY + 1, Ui.TEXT_FAINT)
            chartTop = belowY
            belowY += 50
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

        if (achievements.isNotEmpty()) belowY = drawAchievements(g, centerX, belowY + 4, mouseX, mouseY)

        if (history.isNotEmpty()) {
            drawDeepDive(g, centerX, belowY + 4, p)
            belowY += DEEP_DIVE_HEIGHT
        }

        if (p.modes.isNotEmpty()) drawModes(g, centerX, belowY + 4, p)

        Ui.fadeIn(g, width, height, openedAt)
    }

    /**
     * Earned achievements as a centered, wrapping row of gold chips; hovering a
     * chip prints its description underneath. Returns the Y just past the block
     * so the caller keeps flowing content below it.
     */
    private fun drawAchievements(g: GuiGraphicsExtractor, centerX: Int, top: Int, mouseX: Int, mouseY: Int): Int {
        val gap = 4
        val chipH = font.lineHeight + 4
        val widths = achievements.map { font.width(it.title) + 12 }

        // Greedy wrap into rows no wider than the card.
        val rows = mutableListOf<MutableList<Int>>() // indices into achievements
        var current = mutableListOf<Int>()
        var currentW = 0
        for (i in achievements.indices) {
            val add = widths[i] + if (current.isEmpty()) 0 else gap
            if (current.isNotEmpty() && currentW + add > CARD_WIDTH) {
                rows += current; current = mutableListOf(); currentW = 0
            }
            current += i
            currentW += widths[i] + if (current.size == 1) 0 else gap
        }
        if (current.isNotEmpty()) rows += current

        var hovered: Achievement? = null
        var y = top
        for (row in rows) {
            val rowW = row.sumOf { widths[it] } + gap * (row.size - 1)
            var x = centerX - rowW / 2
            for (i in row) {
                val w = widths[i]
                Ui.chip(g, font, x + w / 2, y, achievements[i].title, ACHIEVEMENT_CHIP_BG, Ui.ACCENT)
                if (mouseX in x..(x + w) && mouseY in y..(y + chipH)) hovered = achievements[i]
                x += w + gap
            }
            y += chipH + 2
        }
        hovered?.let {
            g.centeredText(font, "§7${it.description}", centerX, y, Ui.TEXT_DIM)
            y += 10
        }
        return y + 2
    }

    /**
     * Recent-form dots plus a career stat grid — the numbers MCSR Ranked surfaces
     * on a profile that our raw card does not: peak MMR, best/current streak,
     * season games, best single gain and average game length.
     */
    private fun drawDeepDive(g: GuiGraphicsExtractor, centerX: Int, y: Int, p: PlayerProfile) {
        val left = centerX - CARD_WIDTH / 2

        // Form: last 10 matches, oldest→newest, as coloured dots.
        val recent = history.take(10).reversed()
        g.text(font, "§7Recent form", left, y, Ui.TEXT_FAINT)
        var dx = left + font.width("Recent form ") + 4
        for (e in recent) {
            g.fill(dx, y - 1, dx + 6, y + 6, resultColor(e.result))
            dx += 9
        }

        // Career figures: season totals from the profile, the rest derived from
        // the fetched history window.
        // The server redacts another player's rating when they hide it; guard the
        // history-derived peak too, or it would leak back the number we just hid.
        // Your own profile always shows real figures.
        val hideRating = p.hideRating && p.uuid != RankedState.profile?.uuid
        val ratedHistory = history.filter { it.rated }
        val peak = if (hideRating) null else p.peakRating ?: ratedHistory.mapNotNull { it.ratingAfter }.maxOrNull()
        // The server knows the real streaks — they span every mode and the whole
        // season, not just the history window this screen happened to fetch.
        // Fall back to the window only when the fields are hidden or absent.
        val bestStreak = p.bestStreak ?: longestWinRun()
        val streak = p.currentStreak?.takeIf { it > 0 } ?: currentStreak()
        val games = p.wins + p.losses + p.draws
        val bestGain = ratedHistory.mapNotNull { e -> e.ratingAfter?.let { it - e.ratingBefore } }.maxOrNull() ?: 0
        val avgDur = history.mapNotNull { it.durationSeconds }.let { if (it.isEmpty()) null else it.average().toLong() }
        val avgOpp = history.mapNotNull { it.opponentRating }.let { if (it.isEmpty()) null else it.average().toInt() }
        val bestWin = history.filter { it.result == "win" }.mapNotNull { it.opponentRating }.maxOrNull()

        // Compact tiles (value over label) in a 5×2 grid — ten figures on two short
        // rows instead of a tall list that ran into the form row and the buttons.
        val stats = listOf(
            "Peak" to (peak?.toString() ?: "—"),
            "Games" to games.toString(),
            "Streak" to (if (bestStreak > 0) "§a${bestStreak}W" else "—"),
            "Form" to streakText(streak),
            "Best +" to (if (bestGain > 0) "§a+$bestGain" else "—"),
            "Avg" to (avgDur?.let { Ui.duration(it) } ?: "—"),
            "Time" to Ui.durationLong(p.playtimeSeconds),
            "Forf" to (if (p.forfeits > 0) "§c${p.forfeits}" else "0"),
            "Best W" to (bestWin?.toString() ?: "—"),
            "Avg opp" to (avgOpp?.toString() ?: "—"),
        )

        val headerY = y + 14
        g.text(font, "§7CAREER", left, headerY, Ui.TEXT_FAINT)
        val cols = 5
        val colW = CARD_WIDTH / cols
        val gridY = headerY + 14
        for ((i, s) in stats.withIndex()) {
            val cx = left + (i % cols) * colW + colW / 2
            val vy = gridY + (i / cols) * 20
            g.centeredText(font, s.second, cx, vy, Ui.WHITE)
            g.centeredText(font, "§8${s.first}", cx, vy + 9, Ui.TEXT_FAINT)
        }
    }

    /**
     * Per-mode breakdown: where the time actually went.
     *
     * Each mode carries its own ladder, so a rating here is that mode's, not the
     * headline one; unrated modes simply have none and show their record and
     * playtime instead.
     */
    private fun drawModes(g: GuiGraphicsExtractor, centerX: Int, y: Int, p: PlayerProfile) {
        val left = centerX - CARD_WIDTH / 2
        g.text(font, "§7BY MODE", left, y, Ui.TEXT_FAINT)
        var row = y + 12
        for (mode in p.modes.take(MODE_ROWS)) {
            Ui.row(g, left, row, CARD_WIDTH, 14)
            g.text(font, Ui.fit(font, mode.format.displayName, 96), left + 4, row + 3, Ui.WHITE)

            val record = "${mode.wins}W·${mode.losses}L"
            g.text(font, record, left + 104, row + 3, Ui.TEXT_DIM)

            val rating = mode.rating?.let { "$it" } ?: "—"
            g.text(font, rating, left + 150, row + 3, mode.tier?.let(Ui::tierColor) ?: Ui.TEXT_FAINT)

            Ui.textRight(g, font, Ui.durationLong(mode.playtimeSeconds), left + CARD_WIDTH - 4, row + 3, Ui.TEXT_FAINT)
            row += 15
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

    private fun drawCard(g: GuiGraphicsExtractor, centerX: Int, p: PlayerProfile) {
        val left = centerX - CARD_WIDTH / 2
        val right = left + CARD_WIDTH
        val tierColor = Ui.tierColor(p.tier)

        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)
        Ui.drawUserBackground(g, left + 3, CARD_TOP + 3, CARD_WIDTH - 6, CARD_HEIGHT - 6, p.background)
        // No tier accent bar down the left edge. It reads as a stray line rather
        // than as a tier: an unranked player's is TEXT_DIM, i.e. a grey stripe on
        // a grey panel, and the badge and the tier text already say the tier in
        // the same colour. The bar stays where its colour carries something the
        // card does not — win/loss on a history row, ranked/casual on a mode.

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
        // Stops at the crest slot: a 16-character name in wide glyphs otherwise
        // runs under it, and the flag has already eaten 12px of the run-up.
        g.text(font, Ui.fit(font, p.name, padRight - 34 - nameX), nameX, CARD_TOP + 8, Ui.WHITE)
        g.text(font, p.tier, textLeft, CARD_TOP + 20, tierColor)

        // Another player who hid their rating shows a placeholder instead of the
        // server's zeroed value; the tier badge above still conveys their bracket.
        val hideRating = p.hideRating && p.uuid != RankedState.profile?.uuid
        val mmr = if (hideRating) "MMR hidden" else "${p.rating} MMR"
        val ratingLine = p.rank?.let { "$mmr · Rank #$it" } ?: mmr
        g.text(font, ratingLine, textLeft, CARD_TOP + 32, Ui.TEXT_DIM)

        val rem = p.placementMatchesRemaining
        if (rem > 0) {
            // Placement progress: N of the placement set done, drawn as pips.
            val played = p.wins + p.losses + p.draws
            val total = played + rem
            g.text(font, "§ePlacements $played/$total", textLeft, CARD_TOP + 44, Ui.ACCENT)
            var pipX = textLeft + font.width("Placements $played/$total ") + 2
            for (k in 0 until total) {
                g.fill(pipX, CARD_TOP + 45, pipX + 3, CARD_TOP + 48, if (k < played) Ui.ACCENT else Ui.SLOT_BORDER)
                pipX += 5
            }
        } else {
            // Streak first, season second: form is what an opponent actually
            // wants off this card, and the season number never changes.
            val streak = p.currentStreak ?: 0
            val secondary = if (streak >= 2) "§a$streak win streak" else "Season ${p.season}"
            g.text(font, secondary, textLeft, CARD_TOP + 44, if (streak >= 2) Ui.WIN else Ui.TEXT_FAINT)
        }

        // Endorsement level sits opposite the crest: it is the other thing this
        // player earned that is not their rating.
        p.endorsement?.let { endorsement ->
            val label = "E${endorsement.level}"
            Ui.textRight(g, font, label, padRight, CARD_TOP + 44, Ui.ACCENT)
            Ui.thinProgressBar(
                g,
                padRight - 28, CARD_TOP + 54, 28,
                endorsement.progress.coerceIn(0f, 1f),
                fillColor = Ui.ACCENT,
            )
        }

        val record = "${p.wins}W · ${p.losses}L" +
            if (p.draws > 0) " · ${p.draws}D" else ""
        g.text(font, record, padLeft, CARD_TOP + 62, Ui.TEXT_DIM)
        if (p.wins + p.losses > 0) {
            Ui.textRight(g, font, "${Ui.winRatePercent(p)}%", padRight, CARD_TOP + 62, Ui.TEXT_FAINT)
        }
        Ui.winRateBar(g, padLeft, CARD_TOP + 74, CARD_WIDTH - 20, p.wins, p.losses, p.draws)
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (super.onMouseClicked(event, doubled)) return true
        if (retry.clicked(event.x(), event.y())) return true
        if (event.button() != 0 || chartTop < 0 || points.size < 2) return false
        val left = width / 2 - CARD_WIDTH / 2
        if (event.x() >= left && event.x() <= left + CARD_WIDTH &&
            event.y() >= chartTop && event.y() < chartTop + 44
        ) {
            Sfx.select()
            minecraft.setScreenAndShow(MmrChartScreen(this, points, "$name · last ${points.size} rated matches"))
            return true
        }
        return false
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val CARD_WIDTH = 220
        const val CARD_HEIGHT = 84
        const val CARD_TOP = 40

        /** Dim gold plate behind an earned-achievement chip. */
        const val ACHIEVEMENT_CHIP_BG = 0xFF3A2F12.toInt()

        /** Height the career block occupies: header plus two rows of tiles. */
        const val DEEP_DIVE_HEIGHT = 62

        /** Modes listed before the block would run off a short screen. */
        const val MODE_ROWS = 5
    }
}
