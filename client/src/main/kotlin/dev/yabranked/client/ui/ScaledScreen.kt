package dev.yabranked.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import kotlin.math.ceil

/**
 * The base every ranked screen is built on: a [Screen] that lays itself out in a
 * viewport big enough for its content, whatever GUI scale the player runs.
 *
 * A vanilla screen is laid out in GUI-scaled pixels, so raising the GUI scale
 * *shrinks* the space a screen has to work in — at scale 4 on a 1080p monitor
 * that is 480×270, and the ranked menu alone needs about 420×360. Everything
 * below the fold was drawn off-screen and could not be clicked, which is what
 * the ranked screens looked like on any large GUI scale.
 *
 * So the screens do not lay out in the player's GUI scale. They lay out in a
 * viewport derived from the largest *integer* scale that still fits
 * [DESIGN_WIDTH]×[DESIGN_HEIGHT] — never larger than the player's own choice, so
 * a small GUI scale is left exactly as it was — and everything they draw is
 * multiplied by [uiScale] on the way out. Keeping that step integral is the
 * point: a fractional factor resamples the font and every 1px border, and the
 * result reads as a blurry screenshot rather than a UI.
 *
 * The consequence is that [width]/[height] and the mouse coordinates handed to
 * subclasses are *virtual* — they are the screen's own pixels, not the game's.
 * Anything drawn outside a screen (the notice stack, the queue badge) is still
 * in the game's, and must not be positioned from [width].
 *
 * Subclasses override [layout], [drawBackdrop], [drawContent] and the `onMouse*`
 * hooks instead of the vanilla methods, which are final here — the vanilla ones
 * are where the transform is applied, so an override of them would sit outside
 * it and be laid out in the wrong space.
 */
abstract class ScaledScreen(title: Component) : Screen(title) {

    /** Factor this screen's drawing is multiplied by. `1` when nothing is gained
     *  by scaling down, i.e. the player's GUI scale already fits the design. */
    protected var uiScale: Float = 1f
        private set

    // --- layout ---

    final override fun init() {
        applyViewport()
        layout()
    }

    /** Build the widgets. Runs with [width]/[height] already virtual. */
    protected open fun layout() {}

    /**
     * Picks the viewport. Reads the window rather than the inherited
     * [width]/[height]: `rebuildWidgets()` re-runs [init] *without* resetting
     * them, so deriving the virtual size from the current one would shrink the
     * screen a step further on every refresh.
     */
    private fun applyViewport() {
        val window = Minecraft.getInstance().window
        val guiScale = window.guiScale.coerceAtLeast(1)
        uiScale = fittingScale(window.width, window.height, guiScale).toFloat() / guiScale
        // Ceil: the virtual viewport has to cover the real one completely, or a
        // full-width backdrop leaves a bare strip down the right edge.
        width = ceil(window.guiScaledWidth / uiScale).toInt()
        height = ceil(window.guiScaledHeight / uiScale).toInt()
    }

    // --- rendering ---

    final override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.pose().pushMatrix()
        g.pose().scale(uiScale, uiScale)
        try {
            drawBackdrop(g, virtual(mouseX), virtual(mouseY), partialTick)
        } finally {
            g.pose().popMatrix()
        }
    }

    final override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.pose().pushMatrix()
        g.pose().scale(uiScale, uiScale)
        try {
            val vx = virtual(mouseX)
            val vy = virtual(mouseY)
            drawContent(g, vx, vy, partialTick)
            drawHoverTip(g, vx, vy)
        } finally {
            g.pose().popMatrix()
        }
    }

    /**
     * Draws the hovered button's [RankedButton.hoverTip], last so it sits over
     * every other widget and still inside the transform.
     */
    private fun drawHoverTip(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val tip = children()
            .filterIsInstance<RankedButton>()
            .firstOrNull { it.isHovered }
            ?.hoverTip
            ?: return
        Ui.tooltip(g, Minecraft.getInstance().font, mouseX, mouseY, tip, width)
    }

    /** The screen's backdrop; the vanilla `extractBackground` in virtual space. */
    protected open fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(g, mouseX, mouseY, partialTick)
    }

    /** The screen's content; the vanilla `extractRenderState` in virtual space.
     *  Calling `super.drawContent` draws the widgets, as `super.extractRenderState`
     *  used to. */
    protected open fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
    }

    // --- input ---
    //
    // Every coordinate the game hands us is in its own scale; the widgets were
    // placed in the virtual one, so each has to be divided on the way in.

    final override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean =
        onMouseClicked(virtual(event), doubled)

    final override fun mouseReleased(event: MouseButtonEvent): Boolean =
        onMouseReleased(virtual(event))

    final override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        onMouseDragged(virtual(event), dragX / uiScale, dragY / uiScale)

    final override fun mouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean =
        onMouseScrolled(mouseX / uiScale, mouseY / uiScale, hAmount, vAmount)

    final override fun mouseMoved(mouseX: Double, mouseY: Double) {
        super.mouseMoved(mouseX / uiScale, mouseY / uiScale)
    }

    protected open fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean =
        super.mouseClicked(event, doubled)

    protected open fun onMouseReleased(event: MouseButtonEvent): Boolean =
        super.mouseReleased(event)

    protected open fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean =
        super.mouseDragged(event, dragX, dragY)

    protected open fun onMouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean =
        super.mouseScrolled(mouseX, mouseY, hAmount, vAmount)

    private fun virtual(v: Int): Int = (v / uiScale).toInt()

    private fun virtual(event: MouseButtonEvent): MouseButtonEvent =
        if (uiScale == 1f) event
        else MouseButtonEvent(event.x() / uiScale, event.y() / uiScale, event.buttonInfo())

    companion object {
        /**
         * Smallest viewport a ranked screen is laid out for. The ranked menu is
         * the tallest of them: card, status strip and seven button rows.
         */
        const val DESIGN_WIDTH = 420
        const val DESIGN_HEIGHT = 360

        /**
         * Largest whole scale, no greater than the player's [guiScale], whose
         * viewport still covers [DESIGN_WIDTH]×[DESIGN_HEIGHT] on a
         * [windowWidth]×[windowHeight] window. Returns [guiScale] itself when
         * that already fits, so a player on a small GUI scale sees no change,
         * and never goes below 1 — on a window too small for the design at any
         * scale, a complete-but-small screen still beats a cut-off one.
         */
        fun fittingScale(windowWidth: Int, windowHeight: Int, guiScale: Int): Int {
            var step = guiScale.coerceAtLeast(1)
            while (step > 1 && (windowWidth / step < DESIGN_WIDTH || windowHeight / step < DESIGN_HEIGHT)) {
                step--
            }
            return step
        }
    }
}
