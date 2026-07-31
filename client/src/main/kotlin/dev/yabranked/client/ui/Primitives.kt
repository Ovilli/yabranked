package dev.yabranked.client.ui

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The flat chrome every ranked screen is assembled from: plates, rows, bars and
 * text placement. Nothing here knows what a match or a tier is — that lives in
 * [UiDomain].
 */
open class UiPrimitives : UiPalette() {

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

    /** Card plate: 1px border, flat body. */
    fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) {
        g.fill(x, y, x + width, y + height, PANEL_BORDER)
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, PANEL_BG)
    }

    /**
     * Hollow ring drawn just outside a widget to mark keyboard/controller focus.
     *
     * Sits outside rather than replacing the hover styling, so a widget that is
     * both hovered and focused still reads as two separate things — otherwise
     * tabbing through a screen moves a cursor nobody can see.
     */
    fun focusRing(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, color: Int = ACCENT) {
        val x1 = x + width
        val y1 = y + height
        g.fill(x - 1, y - 1, x1 + 1, y, color)
        g.fill(x - 1, y1, x1 + 1, y1 + 1, color)
        g.fill(x - 1, y, x, y1, color)
        g.fill(x1, y, x1 + 1, y1, color)
    }

    /** A thin accent stripe, used to colour a panel by tier or result. */
    fun accentBar(g: GuiGraphicsExtractor, x: Int, y: Int, height: Int, color: Int) {
        g.fill(x, y, x + 2, y + height, color)
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
        g.fill(x0, y, x1, y + height, alpha(color, 0x22))
        // outward falloff
        g.fill(x0 - 2, y - 1, x1 + 2, y + height + 1, alpha(color, 0x14))
        g.fill(x0 - 4, y - 2, x1 + 4, y + height + 2, alpha(color, 0x0C))
    }

    /** Filled diamond (rotated square) centred at [cx],[cy] with half-diagonal [r]. */
    protected fun diamond(g: GuiGraphicsExtractor, cx: Int, cy: Int, r: Int, color: Int) {
        for (dy in -r..r) {
            val w = r - kotlin.math.abs(dy)
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color)
        }
    }

    /** Straight line via Bresenham — GuiGraphics only fills axis-aligned rects,
     *  so diagonals are walked a pixel at a time. Fine for tiny charts. */
    protected fun line(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, thickness: Int = 1) {
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
        trackColor: Int = TRACK,
        fillColor: Int = ACCENT,
    ) {
        val p = progress.coerceIn(0f, 1f)
        g.fill(x, y, x + width, y + 3, trackColor)
        if (p > 0f) {
            val fillW = (width * p).toInt()
            g.fill(x, y, x + fillW, y + 3, fillColor)
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
        g.fill(x, y, x + 2, y + height, TRACK)
        val maxScroll = total - visible
        val thumbH = maxOf(10, height * visible / total)
        val thumbY = y + (height - thumbH) * scroll.coerceIn(0, maxScroll) / maxScroll
        g.fill(x, thumbY, x + 2, thumbY + thumbH, THUMB)
    }

    /**
     * [scrollbar] laid on its side: a range indicator under a chart that is
     * showing a window of a longer series. Same no-op rule when it all fits.
     */
    fun scrollbarHorizontal(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, total: Int, visible: Int, offset: Int) {
        if (total <= visible) return
        g.fill(x, y, x + width, y + 2, TRACK)
        val maxOffset = total - visible
        val thumbW = maxOf(10, width * visible / total)
        val thumbX = x + (width - thumbW) * offset.coerceIn(0, maxOffset) / maxOffset
        g.fill(thumbX, y, thumbX + thumbW, y + 2, THUMB)
    }

    /**
     * Small boxed hover label near the cursor, clamped to a [screenWidth]-wide
     * screen.
     *
     * The ranked screens draw their own instead of using vanilla's widget
     * tooltip: vanilla defers the draw until after the screen's render pass, so
     * it lands outside [ScaledScreen]'s transform and would be positioned — and
     * sized — in the game's coordinates rather than the screen's.
     */
    fun tooltip(g: GuiGraphicsExtractor, font: Font, mouseX: Int, mouseY: Int, text: String, screenWidth: Int) {
        val w = font.width(text)
        val x = (mouseX + 10).coerceAtMost(screenWidth - w - 6).coerceAtLeast(2)
        val y = (mouseY - 14).coerceAtLeast(2)
        panel(g, x - 3, y - 3, w + 6, 14)
        g.text(font, text, x, y, WHITE)
    }

    /** Trim [text] with an ellipsis so it fits within [maxWidth] pixels. Returns
     *  the original when it already fits. Used to stop list cells overlapping. */
    fun fit(font: Font, text: String, maxWidth: Int): String {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) return text
        var t = text
        while (t.isNotEmpty() && font.width("$t…") > maxWidth) t = t.dropLast(1)
        return "$t…"
    }
}
