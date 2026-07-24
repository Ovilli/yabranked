package dev.yabranked.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * Text button rendered with the mod's own [Ui.buttonPlate] instead of the
 * vanilla plate, so it matches the custom panels. Extends [AbstractWidget]
 * rather than Button because AbstractButton marks its render hook final.
 *
 * The visible label is [getMessage], so callers can retitle it in place (the
 * options toggles rely on this).
 */
class RankedButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    label: Component,
    /** Optional icon-atlas index drawn at the left; see [Ui] ICON_* constants. */
    private val icon: Int? = null,
    private val onPress: () -> Unit,
) : AbstractWidget(x, y, width, height, label) {

    override fun onClick(event: MouseButtonEvent, doubled: Boolean) {
        Minecraft.getInstance().soundManager
            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f))
        onPress()
    }

    /** Previous hover state, so we tick the sound only on the entering edge. */
    private var wasHovered = false

    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // A lighter grey plate than the near-black cards, so buttons read as
        // interactive instead of blending into the panels. Gold border on hover.
        val hovered = isHovered && active
        // Soft tick the moment the cursor lands on the button (mcsr-style),
        // quieter and higher-pitched than the click so it reads as a hover cue.
        if (hovered && !wasHovered) {
            Minecraft.getInstance().soundManager
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f, 0.25f))
        }
        wasHovered = hovered
        val body = when {
            !active -> 0xFF262626.toInt()
            hovered -> 0xFF4A4A4A.toInt()
            else -> 0xFF343434.toInt()
        }
        val border = if (hovered) Ui.ACCENT else 0xFF5A5A5A.toInt()

        g.fill(x, y, x + width, y + height, border)
        g.fill(x + 1, y + 1, x + width - 1, y + height - 1, body)
        // thin top sheen to give the plate a little depth
        g.fill(x + 1, y + 1, x + width - 1, y + 2, 0x18FFFFFF)

        val font = Minecraft.getInstance().font
        // §-codes in the label survive .string, so coloured labels still render;
        // a disabled button is dimmed instead of swapping colour.
        val text = message.string
        // Content tracks the plate: brightens to full white on hover, sits at a
        // soft off-white at rest, dims when disabled. This is the "icon gets
        // lighter on hover" cue — the tint multiplies the icon glyph too.
        val color = when {
            !active -> Ui.TEXT_FAINT
            hovered -> Ui.WHITE
            else -> Ui.TEXT_SOFT
        }

        if (icon != null) {
            Ui.icon(g, icon, x + 6, y + (height - 10) / 2, 10, color)
            // nudge the label right of the icon so the two never touch
            g.centeredText(font, text, x + 8 + width / 2, y + (height - 8) / 2, color)
        } else {
            g.centeredText(font, text, x + width / 2, y + (height - 8) / 2, color)
        }
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        output.add(NarratedElementType.TITLE, message)
    }
}
