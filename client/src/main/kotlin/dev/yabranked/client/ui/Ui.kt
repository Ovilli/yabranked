package dev.yabranked.client.ui

import dev.yabranked.client.WireProfile
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * Shared drawing helpers for the ranked screens. Kept deliberately small:
 * panels, dividers, stat widgets, and the rank crest / VS sprites.
 */
object Ui {
    // --- Text palette: exactly three greyscale roles. Colour hues (WIN/LOSS/
    // ACCENT) are reserved for the win-rate bar, results, and the tier crest —
    // never mixed into body text. One role per line; no mid-string colour
    // switches. This restraint is what keeps the screens from reading noisy.
    const val WHITE = -1                       // PRIMARY   — the one thing that matters on a line
    const val TEXT_DIM = 0xFFA0A0A0.toInt()    // SECONDARY — supporting text
    const val TEXT_FAINT = 0xFF707070.toInt()  // FAINT     — labels, units, timestamps
    const val TEXT_SOFT = 0xFFD2D2D2.toInt()   // resting off-white; brightens to WHITE on hover

    const val PANEL_BG = 0xD0080808.toInt()
    const val PANEL_BORDER = 0xFF555555.toInt()
    // Flat chrome (replaces the old nine-slice plate textures).
    const val ROW_BG = 0xB0141414.toInt()
    const val SLOT_BG = 0xFF0C0C0C.toInt()
    const val SLOT_BORDER = 0xFF3A3A3A.toInt()

    const val WIN = 0xFF5BD97A.toInt()
    const val LOSS = 0xFFE05C5C.toInt()
    const val DRAW = 0xFF9A9A9A.toInt()
    const val ACCENT = 0xFFFFC93C.toInt()

    /** Tier colours, matching the metal each tier is named after. */
    fun tierColor(tier: String): Int = when (tier.substringBefore(' ')) {
        "Coal" -> 0xFF4A4A4A.toInt()
        "Iron" -> 0xFFD8D8D8.toInt()
        "Gold" -> 0xFFFFC93C.toInt()
        "Emerald" -> 0xFF2ECC71.toInt()
        "Diamond" -> 0xFF3FD0D8.toInt()
        "Netherite" -> 0xFF6B6070.toInt()
        else -> TEXT_DIM
    }

    // Icon atlas indices (icons.png: nine 16×16 white glyphs, tinted at blit).
    const val ICON_PLAY = 0
    const val ICON_LEADERBOARD = 1
    const val ICON_HISTORY = 2
    const val ICON_OPTIONS = 3
    const val ICON_REPORT = 4
    const val ICON_BACK = 5
    const val ICON_WIN = 6
    const val ICON_LOSS = 7
    const val ICON_CROWN = 8
    const val ICON_SEARCH = 9
    const val ICON_VS = 10
    const val ICON_GLOBE = 11

    private const val ICON_COUNT = 12

    /** Source dimensions of the full-screen backdrop art. */
    private const val BG_W = 1280
    private const val BG_H = 720

    private fun texture(path: String): Identifier =
        Identifier.fromNamespaceAndPath("yabranked-client", "textures/gui/$path.png")

    /** Blit one glyph from the icon atlas, tinted [color]. */
    fun icon(g: GuiGraphicsExtractor, index: Int, x: Int, y: Int, size: Int = 16, color: Int = WHITE) {
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture("icons"),
            x, y, (index * 16).toFloat(), 0f,
            size, size,
            16, 16,
            ICON_COUNT * 16, 16,
            color,
        )
    }

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

    /** The mod's card glyph, used for the title-screen entry point. */
    fun logo(): Identifier = texture("logo")

    /** Filled diamond (rotated square) centred at [cx],[cy] with half-diagonal [r]. */
    private fun diamond(g: GuiGraphicsExtractor, cx: Int, cy: Int, r: Int, color: Int) {
        for (dy in -r..r) {
            val w = r - kotlin.math.abs(dy)
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color)
        }
    }

    /**
     * Crossed-swords VS medallion for the match-found screen: a gold-rimmed dark
     * diamond with a soft glow, the swords glyph sitting inside. Self-contained —
     * it draws its own highlight, so callers no longer stack a separate glow.
     */
    fun vsEmblem(g: GuiGraphicsExtractor, centerX: Int, y: Int, size: Int = 20) {
        val cy = y + size / 2
        val r = size / 2 + 2
        val glow = ACCENT and 0x00FFFFFF
        diamond(g, centerX, cy, r + 5, (0x10 shl 24) or glow)
        diamond(g, centerX, cy, r + 3, (0x1E shl 24) or glow)
        diamond(g, centerX, cy, r + 1, ACCENT)            // gold rim
        diamond(g, centerX, cy, r - 1, 0xFF16130D.toInt()) // warm-dark body
        val gsz = size - 5
        icon(g, ICON_VS, centerX - gsz / 2, cy - gsz / 2, gsz, ACCENT)
    }

    /** Native pixel size of the flag sprites (ISO 3:2-ish). */
    private const val FLAG_TEX_W = 128
    private const val FLAG_TEX_H = 85

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
        } catch (_: Throwable) {
            // If the texture is missing, fail silently — some users set no country.
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
        try {
            g.blit(
                RenderPipelines.GUI_TEXTURED,
                texture(if (blurred) "background_blur" else "background"),
                0, 0, 0f, 0f,
                width, height,
                BG_W, BG_H,
                BG_W, BG_H,
            )
        } catch (_: Throwable) {
            // missing art: the base fill above already covers the screen
        }
        // Scrim knocks the art back so foreground panels/text stay legible.
        g.fill(0, 0, width, height, scrim shl 24)
    }

    /**
     * Draw a dimmed user background banner. Falls back to "default" on errors.
     * The source is sampled from a 128x128 area (conventional art size) and
     * scaled to [width]×[height]. Any failure is silently ignored.
     */
    fun drawUserBackground(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, id: String?, alpha: Int = 0xAA) {
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
        } catch (_: Throwable) {
            // ignore: missing background or unexpected format
        }
    }

    /**
     * Very lightweight backdrop to make a small icon pop. Draws a few
     * translucent rectangles that expand outward to fake a soft glow — avoids
     * adding a dedicated blurred texture while keeping fill calls cheap.
     */
    fun softGlow(g: GuiGraphicsExtractor, centerX: Int, y: Int, width: Int, height: Int, color: Int) {
        val half = width / 2
        val x0 = centerX - half
        val x1 = centerX + half
        // base fill
        val base = (0x22 shl 24) or (color and 0x00FFFFFF)
        g.fill(x0, y, x1, y + height, base)
        // outward falloff
        val mid = (0x14 shl 24) or (color and 0x00FFFFFF)
        g.fill(x0 - 2, y - 1, x1 + 2, y + height + 1, mid)
        val faint = (0x0C shl 24) or (color and 0x00FFFFFF)
        g.fill(x0 - 4, y - 2, x1 + 4, y + height + 2, faint)
    }

    // Flat chrome, drawn with plain fills instead of nine-slice plate textures
    // — cleaner and closer to the MCSR look than the old muddy PNGs.

    /** Recessed socket that crests and player heads sit inside. */
    fun slot(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
        g.fill(x, y, x + size, y + size, SLOT_BORDER)
        g.fill(x + 1, y + 1, x + size - 1, y + size - 1, SLOT_BG)
    }

    /** List row band — subtly darker than the backdrop, no heavy border. */
    fun row(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        g.fill(x, y, x + width, y + height, ROW_BG)
    }

    /** Gold-edged plate behind screen titles. */
    fun header(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        g.fill(x, y, x + width, y + height, ACCENT)
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_BG)
    }

    private fun darken(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = ((color ushr 16) and 0xFF) * factor
        val gg = ((color ushr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (a shl 24) or (r.toInt() shl 16) or (gg.toInt() shl 8) or b.toInt()
    }

    /** Card plate: 1px border, flat body. */
    fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        g.fill(x, y, x + width, y + height, PANEL_BORDER)
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_BG)
    }

    /** A thin accent stripe, used to colour a panel by tier or result. */
    fun accentBar(g: GuiGraphicsExtractor, x: Int, y: Int, height: Int, color: Int) {
        g.fill(x, y, x + 2, y + height, color)
    }

    /**
     * Win/loss ratio bar. Draws nothing but a dim track when no games are
     * recorded, so a fresh account does not show a misleading full bar.
     */
    fun winRateBar(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, wins: Int, losses: Int, draws: Int) {
        val total = wins + losses + draws
        g.fill(x, y, x + width, y + 3, 0xFF1C1C1C.toInt())
        if (total == 0) return

        var cursor = x
        for ((count, color) in listOf(wins to WIN, draws to DRAW, losses to LOSS)) {
            if (count == 0) continue
            val segment = (width * count) / total
            g.fill(cursor, y, cursor + segment, y + 3, color)
            cursor += segment
        }
    }

    fun winRatePercent(profile: WireProfile): Int {
        val decided = profile.wins + profile.losses
        return if (decided == 0) 0 else (profile.wins * 100) / decided
    }

    /** Right-aligned text helper (the vanilla API only centres or left-aligns). */
    fun textRight(g: GuiGraphicsExtractor, font: Font, text: String, right: Int, y: Int, color: Int) {
        g.text(font, text, right - font.width(text), y, color)
    }

    /**
     * Small rounded badge behind short text (e.g., "+12 MMR"). Uses a simple
     * border + fill so it does not require a separate texture.
     */
    fun chip(
        g: GuiGraphicsExtractor,
        font: Font,
        centerX: Int,
        y: Int,
        text: String,
        bgColor: Int,
        textColor: Int,
    ) {
        val padX = 6
        val padY = 2
        val textW = font.width(text)
        val textH = font.lineHeight
        val width = textW + padX * 2
        val height = textH + padY * 2
        val left = centerX - width / 2
        val top = y

        // Border
        val border = darken(bgColor, 0.75f)
        g.fill(left, top, left + width, top + height, border)
        // Inner fill
        g.fill(left + 1, top + 1, left + width - 1, top + height - 1, bgColor)
        // Text
        val tx = centerX - textW / 2
        val ty = top + padY
        g.text(font, text, tx, ty, textColor)
    }

    /** Simple thin progress bar with a dim track and a coloured fill. */
    fun thinProgressBar(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        width: Int,
        progress: Float,
        trackColor: Int = 0xFF1C1C1C.toInt(),
        fillColor: Int = ACCENT,
    ) {
        val p = progress.coerceIn(0f, 1f)
        g.fill(x, y, x + width, y + 3, trackColor)
        if (p > 0f) {
            val fillW = (width * p).toInt()
            g.fill(x, y, x + fillW, y + 3, fillColor)
        }
    }

    /** Straight line via Bresenham — GuiGraphics only fills axis-aligned rects,
     *  so diagonals are walked a pixel at a time. Fine for tiny charts. */
    private fun line(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, thickness: Int = 1) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            g.fill(x, y, x + thickness, y + thickness, color)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** One sample on the rating chart: the rating after a match and when it was
     *  played (epoch seconds, null if unknown). */
    data class ChartPoint(val rating: Int, val at: Long?)

    /** Back-compat: chart a bare rating series with no timestamps. */
    fun eloChart(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int, width: Int, height: Int, ratings: List<Int>) =
        eloChart(g, font, x, y, width, height, ratings.map { ChartPoint(it, null) }, -1, -1)

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
        points: List<ChartPoint>,
        mouseX: Int = -1,
        mouseY: Int = -1,
    ) {
        Ui.row(g, x, y, width, height)
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

        // Horizontal gridlines at hi / mid / lo of the *data* range, with MMR
        // labels on the right axis so values are readable, not just relative.
        for (lvl in listOf(rawHi, (rawHi + rawLo) / 2, rawLo)) {
            val gy = py(lvl)
            var gx = plotL
            while (gx < plotR) {
                g.fill(gx, gy, gx + 2, gy + 1, 0x1AFFFFFF)
                gx += 4
            }
            textRight(g, font, "§7$lvl", x + width - 3, gy - 3, TEXT_FAINT)
        }

        val up = ratings.last() >= ratings.first()
        val lineColor = if (up) WIN else LOSS
        val areaColor = (0x24 shl 24) or (lineColor and 0x00FFFFFF)

        // Column render: for every pixel of width, interpolate the value, fill the
        // area beneath it, then a 2px line cap. Smoother than segment-to-segment.
        for (cx in 0..plotW) {
            val sx = plotL + cx
            val v = valueAt(if (plotW == 0) 0f else cx.toFloat() / plotW)
            val ly = pyF(v).toInt().coerceIn(plotT, plotB)
            if (ly < plotB) g.fill(sx, ly, sx + 1, plotB, areaColor)
            g.fill(sx, (ly - 1).coerceAtLeast(plotT), sx + 1, (ly + 1).coerceAtMost(plotB), lineColor)
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
            g.fill(plotL, gy, plotR, gy + 1, (0x50 shl 24) or (tc and 0x00FFFFFF))
            g.text(font, tierName.take(1), plotL + 1, gy - 4, (0xC0 shl 24) or (tc and 0x00FFFFFF))
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

        // Bottom time axis: oldest on the left, "now" on the right.
        points.first().at?.let {
            val ago = relativeTime(it)
            if (ago.isNotEmpty()) g.text(font, "§8$ago", plotL, plotB + 1, TEXT_FAINT)
        }
        g.text(font, "§8now", plotR - font.width("now"), plotB + 1, TEXT_FAINT)

        // Hover readout: snap to the nearest sample, guide line + ringed point +
        // a boxed tooltip with MMR, per-match delta and age.
        if (mouseX in plotL..plotR && mouseY in y..(y + height)) {
            val frac = (mouseX - plotL).toFloat() / plotW.coerceAtLeast(1)
            val i = Math.round(frac * (n - 1)).coerceIn(0, n - 1)
            val hx = px(i)
            val hy = py(ratings[i])
            // vertical guide
            var vy = plotT
            while (vy < plotB) { g.fill(hx, vy, hx + 1, vy + 2, 0x40FFFFFF); vy += 4 }
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

    /**
     * A small panel with centred text, for empty / error / loading states so
     * they read as intentional cards rather than text floating on the backdrop.
     */
    fun messageCard(g: GuiGraphicsExtractor, font: Font, centerX: Int, top: Int, text: String, width: Int = 220, height: Int = 38) {
        val left = centerX - width / 2
        panel(g, left, top, width, height)
        g.centeredText(font, text, centerX, top + height / 2 - 4, TEXT_DIM)
    }

    /**
     * Black overlay that fades from opaque to clear over [durationMs] starting at
     * [openedAt], giving screens a soft fade-in instead of a hard pop. Call it
     * last in extractRenderState so it sits on top of content and widgets; it
     * no-ops once the fade has finished.
     */
    fun fadeIn(g: GuiGraphicsExtractor, width: Int, height: Int, openedAt: Long, durationMs: Long = 160) {
        val t = (System.currentTimeMillis() - openedAt).coerceAtLeast(0)
        if (t >= durationMs) return
        val alpha = (255f * (1f - t.toFloat() / durationMs)).toInt().coerceIn(0, 255)
        g.fill(0, 0, width, height, alpha shl 24)
    }

    /**
     * Thin scrollbar for the list screens. Draws a dim track and a brighter
     * thumb sized to the visible fraction; no-ops when everything fits.
     */
    fun scrollbar(g: GuiGraphicsExtractor, x: Int, y: Int, height: Int, total: Int, visible: Int, scroll: Int) {
        if (total <= visible) return
        g.fill(x, y, x + 2, y + height, 0xFF1C1C1C.toInt())
        val maxScroll = total - visible
        val thumbH = maxOf(10, height * visible / total)
        val thumbY = y + (height - thumbH) * scroll.coerceIn(0, maxScroll) / maxScroll
        g.fill(x, thumbY, x + 2, thumbY + thumbH, 0xFF6A6A6A.toInt())
    }

    /** Trim [text] with an ellipsis so it fits within [maxWidth] pixels. Returns
     *  the original when it already fits. Used to stop list cells overlapping. */
    fun fit(font: Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
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
