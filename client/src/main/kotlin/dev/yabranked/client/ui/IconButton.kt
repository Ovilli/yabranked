package dev.yabranked.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents

/**
 * Square button that shows a sprite instead of a label.
 *
 * Extends [AbstractWidget] rather than Button because AbstractButton marks
 * its render hook final, leaving no way to replace the vanilla plate.
 */
class IconButton(
    x: Int,
    y: Int,
    size: Int,
    private val sprite: Identifier,
    /** Native pixel size of [sprite]; scaled to fit the button. */
    private val sourceSize: Int,
    private val label: Component,
    private val onPress: () -> Unit,
) : AbstractWidget(x, y, size, size, label) {

    init {
        setTooltip(Tooltip.create(label))
    }

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        Minecraft.getInstance().soundManager
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f))
        onPress()
    }

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Keyboard/controller focus lights the plate like a hover does, and adds
        // a ring so a focused button is still tellable from a hovered one.
        if (isFocused) Ui.focusRing(g, x, y, width, height)
        Ui.panel(g, x, y, width, height)
        if (isHovered || isFocused) {
            // brighten the plate instead of swapping to a second sprite
            g.fill(x + 1, y + 1, x + width - 1, y + height - 1, Ui.alpha(Ui.ACCENT, 0x33))
        }

        // scale the sprite to the button rather than assuming it fits natively
        val pad = 3
        val drawn = width - pad * 2
        g.blit(
            RenderPipelines.GUI_TEXTURED, sprite,
            x + pad, y + pad, 0f, 0f,
            drawn, drawn,
            sourceSize, sourceSize,
            sourceSize, sourceSize,
        )
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, label)
    }
}
