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
    const val WHITE = -1
    const val TEXT_DIM = 0xFF9A9A9A.toInt()
    const val TEXT_FAINT = 0xFF6E6E6E.toInt()

    const val PANEL_BG = 0xC00E1116.toInt()
    const val PANEL_BORDER = 0xFF2B3140.toInt()

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
        "Netherite" -> 0xFF8E7B9B.toInt()
        else -> TEXT_DIM
    }

    private fun texture(path: String): Identifier =
        Identifier.fromNamespaceAndPath("yabranked-client", "textures/gui/$path.png")

    /**
     * Rank crest sprite for the tier, with division pips beneath it.
     * Sizes are multiples of 16 so the pixel art stays crisp.
     */
    fun rankBadge(g: GuiGraphicsExtractor, x: Int, y: Int, tier: String, size: Int = 16) {
        val sprite = when (tier.substringBefore(' ').lowercase()) {
            "coal", "iron", "gold", "emerald", "diamond", "netherite" ->
                tier.substringBefore(' ').lowercase()
            else -> "unranked"
        }
        // The short blit overload reuses the destination size as the source
        // size, which samples past the edge of a 16x16 sprite whenever it is
        // scaled up — always pass the source region explicitly.
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture("rank/$sprite"),
            x, y, 0f, 0f,
            size, size,
            16, 16,
            16, 16,
        )

        val division = when {
            tier.endsWith(" III") -> 3
            tier.endsWith(" II") -> 2
            tier.endsWith(" I") -> 1
            else -> 0
        }
        if (division == 0) return

        // pips are white in the sprite sheet and tinted to the tier colour
        val pipWidth = size / 2
        val pipHeight = size / 8
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture("rank/pips"),
            x + (size - pipWidth) / 2, y + size,
            0f, 0f,
            pipWidth * division / 3, pipHeight,
            16 * division / 3, 4,
            16, 4,
            tierColor(tier),
        )
    }

    /**
     * Crossed-swords emblem for the match-found screen. Deliberately a symbol
     * rather than baked "VS" lettering: text belongs in the game font, which
     * renders letterforms correctly and can be translated.
     */
    fun vsEmblem(g: GuiGraphicsExtractor, centerX: Int, y: Int, size: Int = 20) {
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture("vs"),
            centerX - size / 2, y, 0f, 0f,
            size, size,
            20, 20,
            20, 20,
        )
    }

    /**
     * Draws [texture] as a nine-slice so panels keep crisp corners at any
     * size. [corner] must be small enough that two corners fit the target.
     */
    private fun nineSlice(
        g: GuiGraphicsExtractor,
        sprite: Identifier,
        x: Int, y: Int, width: Int, height: Int,
        source: Int, corner: Int,
    ) {
        val c = minOf(corner, width / 2, height / 2)
        if (c <= 0) return
        val midW = width - c * 2
        val midH = height - c * 2
        val srcMid = source - corner * 2
        val far = (source - corner).toFloat()

        fun piece(dx: Int, dy: Int, dw: Int, dh: Int, u: Float, v: Float, sw: Int, sh: Int) {
            if (dw <= 0 || dh <= 0) return
            g.blit(
                RenderPipelines.GUI_TEXTURED, sprite,
                x + dx, y + dy, u, v,
                dw, dh,
                sw, sh,
                source, source,
            )
        }

        piece(0, 0, c, c, 0f, 0f, corner, corner)
        piece(width - c, 0, c, c, far, 0f, corner, corner)
        piece(0, height - c, c, c, 0f, far, corner, corner)
        piece(width - c, height - c, c, c, far, far, corner, corner)
        piece(c, 0, midW, c, corner.toFloat(), 0f, srcMid, corner)
        piece(c, height - c, midW, c, corner.toFloat(), far, srcMid, corner)
        piece(0, c, c, midH, 0f, corner.toFloat(), corner, srcMid)
        piece(width - c, c, c, midH, far, corner.toFloat(), corner, srcMid)
        piece(c, c, midW, midH, corner.toFloat(), corner.toFloat(), srcMid, srcMid)
    }

    /** Recessed socket that crests and player heads sit inside. */
    fun slot(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int) =
        nineSlice(g, texture("slot"), x, y, size, size, source = 24, corner = 6)

    /** List row background, flatter than [panel] so stacked rows stay calm. */
    fun row(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) =
        nineSlice(g, texture("row"), x, y, width, height, source = 24, corner = 4)

    /** Gold-edged plate used behind screen titles. */
    fun header(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) =
        nineSlice(g, texture("header"), x, y, width, height, source = 24, corner = 8)

    private fun darken(color: Int, factor: Float): Int {
        val a = (color ushr 24) and 0xFF
        val r = ((color ushr 16) and 0xFF) * factor
        val gg = ((color ushr 8) and 0xFF) * factor
        val b = (color and 0xFF) * factor
        return (a shl 24) or (r.toInt() shl 16) or (gg.toInt() shl 8) or b.toInt()
    }

    fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int) =
        nineSlice(g, texture("panel"), x, y, width, height, source = 24, corner = 8)

    /** A thin accent stripe, used to colour a panel by tier or result. */
    fun accentBar(g: GuiGraphicsExtractor, x: Int, y: Int, height: Int, color: Int) {
        g.fill(x, y, x + 2, y + height, color)
    }

    /** Ornamental divider that fades out at both ends. */
    fun divider(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int) {
        g.blit(
            RenderPipelines.GUI_TEXTURED, texture("divider"),
            x, y, 0f, 0f,
            width, 3,
            32, 3,
            32, 3,
        )
    }

    /**
     * Win/loss ratio bar. Draws nothing but a dim track when no games are
     * recorded, so a fresh account does not show a misleading full bar.
     */
    fun winRateBar(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, wins: Int, losses: Int, draws: Int) {
        val total = wins + losses + draws
        g.fill(x, y, x + width, y + 3, 0xFF23262E.toInt())
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

    /** "12:34" from seconds; "—" when unknown. */
    fun duration(seconds: Long?): String {
        if (seconds == null) return "—"
        return "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
