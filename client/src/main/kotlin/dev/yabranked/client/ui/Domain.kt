package dev.yabranked.client.ui

import dev.yabranked.proto.PlayerProfile
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * The helpers that know about ranked concepts rather than just pixels: tier
 * crests, country flags, menu backdrops, win/loss records, match durations and
 * the rating chart.
 */
open class UiDomain : UiIcons() {

    /** Source dimensions of the full-screen backdrop art. */
    private val BG_W = 1280
    private val BG_H = 720

    /** Native pixel size of the flag sprites (ISO 3:2-ish). */
    private val FLAG_TEX_W = 128
    private val FLAG_TEX_H = 85

    private fun flagTexture(code: String): Identifier =
        Identifier.fromNamespaceAndPath("yabranked-client", "textures/flags/${code.lowercase()}.png")

    private fun userBackgroundTexture(id: String): Identifier =
        // Backgrounds live under textures/gui/user_background/<id>.png
        texture("user_background/${id.lowercase()}")

    /**
     * Division within a tier, parsed from the label ("Gold II" → 2). Returns 0
     * for tiers without divisions (Netherite, Unranked). Higher = closer to the
     * next tier, matching the backend's rating bands.
     */
    private fun division(tier: String): Int = when (tier.substringAfter(' ', "").trim()) {
        "I" -> 1
        "II" -> 2
        "III" -> 3
        else -> 0
    }

    /**
     * ARGB brightness tint that separates the three divisions of a tier without
     * needing three sprites: the higher the division, the brighter the crest.
     * Kept white (no tint) for divisionless tiers.
     */
    private fun divisionTint(tier: String): Int = when (division(tier)) {
        1 -> 0xFFAAAAAA.toInt()
        2 -> 0xFFD4D4D4.toInt()
        3 -> WHITE
        else -> WHITE
    }

    /**
     * Rank crest sprite for the tier, brightness-tinted by division so "Gold I"
     * through "Gold III" are distinguishable from one crest. The division is not
     * drawn as pips — those collided with the tier label below the crest.
     */
    fun rankBadge(g: GuiGraphicsExtractor, x: Int, y: Int, tier: String, size: Int = 16) {
        val sprite = when (tier.substringBefore(' ').lowercase()) {
            "coal", "iron", "gold", "emerald", "diamond", "netherite" ->
                tier.substringBefore(' ').lowercase()
            else -> "unranked"
        }
        // The short blit overload reuses the destination size as the source
        // size, which samples past the edge of a 16x16 sprite whenever it is
        // scaled up — always pass the source region explicitly. The trailing
        // argument multiplies the sprite by an ARGB tint (WHITE = untouched).
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture("rank/$sprite"),
            x, y, 0f, 0f,
            size, size,
            16, 16,
            16, 16,
            divisionTint(tier),
        )
    }

    /** Height a flag drawn [width] px wide takes, preserving the sprite aspect. */
    fun flagHeight(width: Int): Int = maxOf(1, (width * FLAG_TEX_H) / FLAG_TEX_W)

    /**
     * Draws a country flag if available; no-ops on missing code/asset. [size] is
     * the drawn *width*; the height follows the sprite's real 128×85 aspect so the
     * whole flag shows (sampling a 16×16 corner only drew the top-left stripe).
     */
    fun flagIcon(g: GuiGraphicsExtractor, x: Int, y: Int, code: String?, size: Int = 9) {
        if (code == null || code.length != 2) return
        try {
            g.blit(
                RenderPipelines.GUI_TEXTURED, flagTexture(code),
                x, y, 0f, 0f,
                size, flagHeight(size),
                FLAG_TEX_W, FLAG_TEX_H,
                FLAG_TEX_W, FLAG_TEX_H,
            )
        } catch (e: Throwable) {
            // A player with no country is normal, so this must never be fatal —
            // but report it once, otherwise a genuinely broken flag asset looks
            // exactly like "nobody set a country".
            textureFailed("flags/${code.lowercase()}", e)
        }
    }

    /**
     * Full-screen menu backdrop, replacing vanilla's dirt/panorama on the ranked
     * screens. [blurred] swaps the sharp art for the pre-blurred copy — used on
     * the text-heavy list/util screens so labels read cleanly. A dark scrim and a
     * dark scrim on top keeps foreground panels and text legible. Call right
     * after super.extractRenderState, before any content.
     */
    fun drawBackground(g: GuiGraphicsExtractor, width: Int, height: Int, blurred: Boolean = false, scrim: Int = 0x88) {
        // Opaque base first, so the game world never shows through even if the art
        // texture is still uploading or fails to bind.
        g.fill(0, 0, width, height, 0xFF0D0B0A.toInt())
        val art = if (blurred) "background_blur" else "background"
        try {
            g.blit(
                RenderPipelines.GUI_TEXTURED,
                texture(art),
                0, 0, 0f, 0f,
                width, height,
                BG_W, BG_H,
                BG_W, BG_H,
            )
        } catch (e: Throwable) {
            // missing art: the base fill above already covers the screen, so the
            // screen stays usable — the log line is the only clue it is missing
            textureFailed("gui/$art", e)
        }
        // Scrim knocks the art back so foreground panels/text stay legible.
        g.fill(0, 0, width, height, scrim shl 24)
    }

    /**
     * Draw a dimmed user background banner. The source is sampled from a 128x128
     * area (conventional art size) and scaled to [width]×[height]. A failure
     * leaves the banner blank rather than throwing, and is logged once.
     *
     * [alpha] is the black scrim laid over the art: high enough that text stays
     * readable over busy backgrounds, low enough that the art the player picked
     * is actually recognisable. 0xAA erased everything but the boldest images,
     * which made the whole feature look broken; 0x55 keeps text legible on all
     * the shipped art. Callers over denser text can pass more.
     */
    fun drawUserBackground(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, id: String?, alpha: Int = 0x55) {
        val key = (id?.ifBlank { null } ?: "default").lowercase()
        try {
            g.blit(
                RenderPipelines.GUI_TEXTURED, userBackgroundTexture(key),
                x, y, 0f, 0f,
                width, height,
                128, 128,
                128, 128,
            )
            // Dim overlay for legibility over busy art
            val dim = (alpha shl 24)
            g.fill(x, y, x + width, y + height, dim)
        } catch (e: Throwable) {
            // Never fatal — the banner just stays empty — but "the background does
            // nothing" is undiagnosable without knowing which id failed to bind.
            textureFailed("gui/user_background/$key", e)
        }
    }

    /**
     * Win/loss ratio bar. Draws nothing but a dim track when no games are
     * recorded, so a fresh account does not show a misleading full bar.
     */
    fun winRateBar(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, wins: Int, losses: Int, draws: Int) {
        val total = wins + losses + draws
        g.fill(x, y, x + width, y + 3, TRACK)
        if (total == 0) return

        var cursor = x
        for ((count, color) in listOf(wins to WIN, draws to DRAW, losses to LOSS)) {
            if (count == 0) continue
            val segment = (width * count) / total
            g.fill(cursor, y, cursor + segment, y + 3, color)
            cursor += segment
        }
    }

    fun winRatePercent(profile: PlayerProfile): Int {
        val decided = profile.wins + profile.losses
        return if (decided == 0) 0 else (profile.wins * 100) / decided
    }

    /** Back-compat: chart a bare rating series with no timestamps. */
    fun eloChart(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int, height: Int, ratings: List<Int>) =
        eloChart(g, font, x, y, width, height, ratings.map { Ui.ChartPoint(it, null) }, -1, -1)

    /**
     * Rating-over-time chart (MCSR EloChangeChartWidget analogue), [points]
     * oldest→newest. Auto-scales to the data with an MMR axis and gridlines, an
     * area fill under a trend line coloured by net gain/loss, a peak marker, and
     * — when [mouseX]/[mouseY] land on the plot — a hover readout of the nearest
     * point's MMR, delta and age.
     */
    fun eloChart(
        g: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        points: List<Ui.ChartPoint>,
        mouseX: Int = -1,
        mouseY: Int = -1,
    ) {
        row(g, x, y, width, height)
        if (points.size < 2) {
            g.centeredText(font, "§8Not enough matches to chart", x + width / 2, y + height / 2 - 4, TEXT_FAINT)
            return
        }

        val n = points.size
        val ratings = points.map { it.rating }
        val padL = 6
        val padR = 32 // room for the MMR axis labels on the right
        val padT = 6
        val padB = 9  // room for the time labels along the bottom
        val plotW = width - padL - padR
        val plotH = height - padT - padB
        val plotL = x + padL
        val plotR = x + padL + plotW
        val plotT = y + padT
        val plotB = y + padT + plotH

        // Pad the value range a touch so the line never rides the very edge.
        val rawHi = ratings.max()
        val rawLo = ratings.min()
        val pad = ((rawHi - rawLo) / 8).coerceAtLeast(4)
        val hi = rawHi + pad
        val lo = rawLo - pad
        val span = (hi - lo).coerceAtLeast(1)

        fun pxF(i: Float) = plotL + (plotW * i / (n - 1))
        fun px(i: Int) = pxF(i.toFloat()).toInt()
        fun pyF(v: Float) = plotT + (plotH * (hi - v) / span)
        fun py(v: Int) = pyF(v.toFloat()).toInt()
        fun valueAt(frac: Float): Float {
            val posF = (frac.coerceIn(0f, 1f)) * (n - 1)
            val i0 = posF.toInt().coerceIn(0, n - 1)
            val i1 = (i0 + 1).coerceAtMost(n - 1)
            val f = posF - i0
            return ratings[i0] + (ratings[i1] - ratings[i0]) * f
        }

        // Horizontal gridlines across the data range, more on a taller plot, each
        // with an MMR label on the right axis so values read absolute, not relative.
        val gridLevels = if (plotH >= 120) 6 else if (plotH >= 80) 5 else 3
        for (k in 0 until gridLevels) {
            val lvl = rawLo + (rawHi - rawLo) * k / (gridLevels - 1)
            val gy = py(lvl)
            var gx = plotL
            while (gx < plotR) {
                g.fill(gx, gy, gx + 2, gy + 1, alpha(WHITE, 0x16))
                gx += 4
            }
            textRight(g, font, "§7$lvl", x + width - 3, gy - 3, TEXT_FAINT)
        }

        val up = ratings.last() >= ratings.first()
        val lineColor = if (up) WIN else LOSS
        // Thicker line + a real vertical gradient (bright near the line, fading to
        // near-transparent at the floor) scale the chart up without looking sparse.
        val thick = if (plotH >= 120) 1 else 0
        val gradH = (plotH / 2).coerceAtLeast(8)
        val areaBase = alpha(lineColor, 0x0E)
        val areaGlow = alpha(lineColor, 0x30)

        // Column render: for every pixel of width, interpolate the value, fill the
        // area beneath it, then the line cap. Smoother than segment-to-segment.
        for (cx in 0..plotW) {
            val sx = plotL + cx
            val v = valueAt(if (plotW == 0) 0f else cx.toFloat() / plotW)
            val ly = pyF(v).toInt().coerceIn(plotT, plotB)
            if (ly < plotB) {
                g.fill(sx, ly, sx + 1, plotB, areaBase)            // dim base to the floor
                val glowBot = (ly + gradH).coerceAtMost(plotB)
                g.fill(sx, ly, sx + 1, glowBot, areaGlow)          // brighter band near the line
            }
            g.fill(sx, (ly - 1 - thick).coerceAtLeast(plotT), sx + 1, (ly + 1 + thick).coerceAtMost(plotB), lineColor)
        }

        // Tier threshold lines: a faint tier-coloured rule at each metal floor
        // that falls inside the visible range, with the tier initial on the left.
        val tierFloors = listOf(
            600 to "Iron", 900 to "Gold", 1200 to "Emerald", 1500 to "Diamond", 1800 to "Netherite",
        )
        for ((floor, tierName) in tierFloors) {
            if (floor <= lo || floor >= hi) continue
            val gy = py(floor)
            val tc = tierColor(tierName)
            g.fill(plotL, gy, plotR, gy + 1, alpha(tc, 0x50))
            g.text(font, tierName.take(1), plotL + 1, gy - 4, alpha(tc, 0xC0))
        }

        // Detect tier crossings so the matches where a promotion/demotion happened
        // stand out: a rating that steps up through a floor is a promotion (gold),
        // stepping down through one is a demotion (red).
        fun crossing(i: Int): Int {
            if (i == 0) return 0
            for ((floor, _) in tierFloors) {
                if (ratings[i - 1] < floor && ratings[i] >= floor) return 1   // promo
                if (ratings[i - 1] >= floor && ratings[i] < floor) return -1  // demo
            }
            return 0
        }

        // Point markers; crossings emphasised, peak in gold, current in white.
        val peakIdx = ratings.indices.maxByOrNull { ratings[it] } ?: 0
        for (i in ratings.indices) {
            val isLast = i == ratings.lastIndex
            val cross = crossing(i)
            val s = if (isLast || cross != 0 || i == peakIdx) 4 else 2
            val cx = px(i) - s / 2
            val cy = py(ratings[i]) - s / 2
            val col = when {
                cross > 0 -> ACCENT
                cross < 0 -> LOSS
                isLast -> WHITE
                i == peakIdx -> ACCENT
                else -> lineColor
            }
            // white pip under emphasised markers so they read on any background
            if (s >= 4) g.fill(cx - 1, cy - 1, cx + s + 1, cy + s + 1, 0xAA000000.toInt())
            g.fill(cx, cy, cx + s, cy + s, col)
        }

        // Net change chip, top-left, out of the way of the axis labels.
        val delta = ratings.last() - ratings.first()
        val deltaText = if (delta >= 0) "§a▲ +$delta" else "§c▼ $delta"
        g.text(font, deltaText, plotL + 1, plotT - 1, WHITE)

        // Bottom time axis. On a wide plot spread several dated ticks across it;
        // otherwise just oldest-left / now-right.
        if (plotW >= 240 && points.first().at != null) {
            val ticks = 4
            for (k in 0..ticks) {
                val idx = Math.round(k.toFloat() / ticks * (n - 1)).toInt().coerceIn(0, n - 1)
                val label = if (k == ticks) "now" else points[idx].at?.let { relativeTime(it) }.orEmpty()
                if (label.isEmpty()) continue
                val tw = font.width(label)
                val tx = when (k) {
                    0 -> plotL
                    ticks -> plotR - tw
                    else -> (px(idx) - tw / 2).coerceIn(plotL, plotR - tw)
                }
                g.text(font, "§8$label", tx, plotB + 1, TEXT_FAINT)
                g.fill(px(idx), plotB, px(idx) + 1, plotB + 2, alpha(WHITE, 0x30)) // tick mark
            }
        } else {
            points.first().at?.let {
                val ago = relativeTime(it)
                if (ago.isNotEmpty()) g.text(font, "§8$ago", plotL, plotB + 1, TEXT_FAINT)
            }
            g.text(font, "§8now", plotR - font.width("now"), plotB + 1, TEXT_FAINT)
        }

        // Hover readout: snap to the nearest sample, crosshair guides + ringed
        // point + a boxed tooltip with MMR, per-match delta and age.
        if (mouseX in plotL..plotR && mouseY in y..(y + height)) {
            val frac = (mouseX - plotL).toFloat() / plotW.coerceAtLeast(1)
            val i = Math.round(frac * (n - 1)).coerceIn(0, n - 1)
            val hx = px(i)
            val hy = py(ratings[i])
            // vertical guide
            var vy = plotT
            while (vy < plotB) { g.fill(hx, vy, hx + 1, vy + 2, alpha(WHITE, 0x40)); vy += 4 }
            // horizontal guide at the point's value
            var hxg = plotL
            while (hxg < plotR) { g.fill(hxg, hy, hxg + 2, hy + 1, alpha(WHITE, 0x30)); hxg += 4 }
            // ring around the point
            g.fill(hx - 2, hy - 2, hx + 3, hy - 1, WHITE)
            g.fill(hx - 2, hy + 2, hx + 3, hy + 3, WHITE)
            g.fill(hx - 2, hy - 1, hx - 1, hy + 2, WHITE)
            g.fill(hx + 2, hy - 1, hx + 3, hy + 2, WHITE)

            val d = if (i > 0) ratings[i] - ratings[i - 1] else 0
            val dTxt = if (i == 0) "" else if (d >= 0) " §a+$d" else " §c$d"
            val age = points[i].at?.let { relativeTime(it) }.orEmpty()
            val label = "§f${ratings[i]} MMR$dTxt" + if (age.isNotEmpty()) "  §8$age" else ""
            val w = font.width(label)
            val bx = (hx + 4).coerceAtMost(x + width - w - 5)
            val by = (hy - 12).coerceAtLeast(y + 1)
            panel(g, bx - 3, by - 2, w + 6, 12)
            g.text(font, label, bx, by, WHITE)
        }
    }

    /** "12:34" from seconds; "—" when unknown. */
    fun duration(seconds: Long?): String {
        if (seconds == null) return "—"
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }

    /** Compact total-time label for large spans: "3h 12m", "42m", "18s". */
    fun durationLong(seconds: Long?): String {
        if (seconds == null || seconds <= 0) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m"
            else -> "${seconds}s"
        }
    }

    /**
     * Coarse "how long ago" from an epoch-**seconds** timestamp (what the backend
     * sends for completedAt): "just now", "5m", "3h", "2d", "4w". Blank when
     * unknown. Guards against clock skew so a match played seconds ago never
     * reads as a huge negative/positive age.
     */
    fun relativeTime(epochSeconds: Long?): String {
        if (epochSeconds == null || epochSeconds <= 0) return ""
        val secs = System.currentTimeMillis() / 1000 - epochSeconds
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60}m ago"
            secs < 86400 -> "${secs / 3600}h ago"
            secs < 604800 -> "${secs / 86400}d ago"
            else -> "${secs / 604800}w ago"
        }
    }
}
