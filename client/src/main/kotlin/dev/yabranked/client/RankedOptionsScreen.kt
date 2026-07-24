package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Client options, modelled on MCSR Ranked's options menu. Each row is an on/off
 * toggle bound to a [RankedState] flag; flipping one persists immediately via
 * [Config] so the choice survives a restart.
 */
class RankedOptionsScreen(
    private val parent: Screen?,
) : Screen(Component.literal("YAB Ranked Options")) {

    private val toggles = mutableListOf<Toggle>()

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    private class Toggle(
        val label: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
        val hint: String,
    ) {
        var button: RankedButton? = null
    }

    override fun init() {
        toggles.clear()
        toggles += Toggle("Country flags", { RankedState.showFlags }, { RankedState.showFlags = it },
            "Show country flags next to names.")
        toggles += Toggle("Hide my flag", { RankedState.hideOwnFlag }, { RankedState.hideOwnFlag = it },
            "Keep your own flag hidden from others' view of you.")
        toggles += Toggle("Hide my MMR", { RankedState.hideElo }, { RankedState.hideElo = it },
            "Hide your rating on your profile and results.")
        toggles += Toggle("Hide opponent MMR", { RankedState.hideOpponentElo }, { RankedState.hideOpponentElo = it },
            "Hide the opponent's rating in match-found and the HUD.")

        val centerX = width / 2
        var y = height / 2 - (toggles.size * ROW) / 2 - 4
        for (t in toggles) {
            val b = RankedButton(centerX - 120, y, 240, 20, rowLabel(t)) {
                t.set(!t.get())
                Config.save()
                Sfx.toggle(t.get())
                t.button?.message = rowLabel(t)
            }
            t.button = addRenderableWidget(b)
            y += ROW
        }

        addRenderableWidget(
            RankedButton(centerX - 120, y + 6, 240, 20, countryLabel(), Ui.ICON_GLOBE) {
                Config.save()
                minecraft.setScreenAndShow(CountryPickerScreen(this))
            }
        )
        addRenderableWidget(
            RankedButton(centerX - 100, y + 32, 200, 20, Component.literal("Done")) { onClose() }
        )
        Sfx.open()
    }

    private fun countryLabel(): Component {
        val code = RankedState.profile?.country
        val value = if (code != null) CountryData.name(code) else "§7None"
        return Component.literal("§fMy country: $value")
    }

    private fun rowLabel(t: Toggle): Component {
        val state = if (t.get()) "§aON" else "§7OFF"
        return Component.literal("§f${t.label}: $state")
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        val centerX = width / 2
        val top = height / 2 - (toggles.size * ROW) / 2 - 4
        g.centeredText(font, "§lOPTIONS", centerX, top - 24, Ui.ACCENT)

        // Hover hint for the toggle under the cursor.
        toggles.firstOrNull { t ->
            val b = t.button ?: return@firstOrNull false
            mouseX >= b.x && mouseX < b.x + b.width && mouseY >= b.y && mouseY < b.y + b.height
        }?.let { g.centeredText(font, "§7${it.hint}", centerX, height - 44, Ui.TEXT_DIM) }

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    override fun onClose() {
        Config.save()
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val ROW = 24
    }
}
