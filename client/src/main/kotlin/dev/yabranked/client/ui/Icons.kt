package dev.yabranked.client.ui

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("yabranked-client")

/**
 * The sprite side of the toolkit: texture lookup, the icon atlas, and the
 * composite emblems built out of them.
 */
open class UiIcons : UiPrimitives() {

    // Icon atlas indices (icons.png: nine 16×16 white glyphs, tinted at blit).
    val ICON_PLAY = 0
    val ICON_LEADERBOARD = 1
    val ICON_HISTORY = 2
    val ICON_OPTIONS = 3
    val ICON_REPORT = 4
    val ICON_BACK = 5
    val ICON_WIN = 6
    val ICON_LOSS = 7
    val ICON_CROWN = 8
    val ICON_SEARCH = 9
    val ICON_VS = 10
    val ICON_GLOBE = 11

    private val ICON_COUNT = 12

    /** Texture ids already reported by [textureFailed]. Render-thread only, so a
     *  plain set needs no synchronisation. */
    private val reportedTextures = mutableSetOf<String>()

    /**
     * Report a texture that would not draw — once per id, ever.
     *
     * The blit helpers run every frame, so logging each failure as it happens
     * would bury the log under thousands of identical lines a second. One line
     * per broken asset is enough to tell "missing", "wrong format" and "drew
     * fine but is invisible" apart, which is otherwise impossible.
     */
    protected fun textureFailed(id: String, e: Throwable) {
        if (reportedTextures.add(id)) log.warn("failed to draw texture $id", e)
    }

    protected fun texture(path: String): Identifier =
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

    /** The mod's card glyph, used for the title-screen entry point. */
    fun logo(): Identifier = texture("logo")

    /**
     * Crossed-swords VS medallion for the match-found screen: a gold-rimmed dark
     * diamond with a soft glow, the swords glyph sitting inside. Self-contained —
     * it draws its own highlight, so callers no longer stack a separate glow.
     */
    fun vsEmblem(g: GuiGraphicsExtractor, centerX: Int, y: Int, size: Int = 20) {
        val cy = y + size / 2
        val r = size / 2 + 2
        diamond(g, centerX, cy, r + 5, alpha(ACCENT, 0x10))
        diamond(g, centerX, cy, r + 3, alpha(ACCENT, 0x1E))
        diamond(g, centerX, cy, r + 1, ACCENT)            // gold rim
        diamond(g, centerX, cy, r - 1, 0xFF16130D.toInt()) // warm-dark body
        val gsz = size - 5
        icon(g, ICON_VS, centerX - gsz / 2, cy - gsz / 2, gsz, ACCENT)
    }
}
