package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Client options, modelled on MCSR Ranked's options menu. Each row is an on/off
 * toggle bound to a [RankedState] flag; flipping one persists immediately via
 * [Config] so the choice survives a restart.
 */
class RankedOptionsScreen(
    private val parent: Screen?,
) : ScaledScreen(Component.literal("YAB Ranked Options")) {

    private val toggles = mutableListOf<Toggle>()

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    /** Top of the toggle block; set during [init] so the title is drawn against
     *  the same value the buttons were laid out on. */
    private var top = 0

    private class Toggle(
        val label: String,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit,
        val hint: String,
    ) {
        var button: RankedButton? = null
    }

    private val opened = FirstInit()

    override fun layout() {
        toggles.clear()
        toggles += Toggle("Country flags", { RankedState.showFlags }, { RankedState.showFlags = it },
            "Show country flags next to names.")
        toggles += Toggle("Hide my flag", { RankedState.hideOwnFlag }, {
            RankedState.hideOwnFlag = it; pushPrivacy()
        }, "Keep your flag hidden from other players' view of you.")
        toggles += Toggle("Hide my MMR", { RankedState.hideElo }, {
            RankedState.hideElo = it; pushPrivacy()
        }, "Hide your exact rating on your public profile and match reveals.")
        toggles += Toggle("Hide opponent MMR", { RankedState.hideOpponentElo }, { RankedState.hideOpponentElo = it },
            "Hide the opponent's rating in match-found and the HUD.")
        toggles += Toggle("Colourblind mode", { RankedState.colorblind },
            { RankedState.colorblind = it; Ui.colorblindPalette = it },
            "Blue/orange win-loss colours instead of green/red.")

        val centerX = width / 2
        top = layoutTop()
        var y = top
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
            RankedButton(centerX - 120, y + 6, 118, 20, countryLabel(), Ui.ICON_GLOBE) {
                Config.save()
                minecraft.setScreenAndShow(CountryPickerScreen(this))
            }
        )
        addRenderableWidget(
            RankedButton(centerX + 2, y + 6, 118, 20, backgroundLabel()) {
                Config.save()
                minecraft.setScreenAndShow(BackgroundPickerScreen(this))
            }
        )
        addRenderableWidget(
            RankedButton(centerX - 120, y + 32, 240, 20, Component.literal("Privacy…")) {
                Config.save()
                minecraft.setScreenAndShow(PrivacyScreen(this))
            }
        )
        // Done is pinned to the bottom edge like every other ranked screen's
        // Back/Done. Trailing the flowed block put it at `height/2 + 114`,
        // which is off the bottom of the screen for any GUI height under 268 —
        // including the 426×240 a 720p client gets at the default auto scale.
        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Done"), Ui.ICON_BACK) { onClose() }
        )
        opened.once { Sfx.open() }
    }

    /**
     * Top of the toggle block: vertically centred when there is room, else
     * lifted so the Privacy row still clears the hover-hint strip and the Done
     * bar. Never above [MIN_TOP], which is what keeps the "OPTIONS" title
     * (drawn 24px higher) on screen.
     */
    private fun layoutTop(): Int {
        // Toggle rows, then the country/card pair at +6 and Privacy at +32,
        // both 20 tall — 52px of trailer past the last toggle.
        val block = toggles.size * ROW + 52
        val centred = height / 2 - (toggles.size * ROW) / 2 - 4
        return minOf(centred, height - BOTTOM_STRIP - block).coerceAtLeast(MIN_TOP)
    }

    /**
     * Push the flag/rating privacy prefs to the backend so they actually apply
     * to other players' views. These toggles are the only client-editable server
     * state here, so unlike the purely local toggles they need a round-trip; the
     * refreshed profile is cached back into [RankedState].
     */
    private fun pushPrivacy() {
        val backend = RankedState.backend ?: return
        val hideFlag = RankedState.hideOwnFlag
        val hideRating = RankedState.hideElo
        YabRankedClient.workers.execute {
            val updated = backend.updateProfile(hideFlag = hideFlag, hideRating = hideRating)
            if (updated != null) minecraft.execute { RankedState.profile = updated }
        }
    }

    private fun countryLabel(): Component {
        val code = RankedState.profile?.country
        val value = if (code != null) CountryData.name(code) else "§7None"
        return Component.literal("§fCountry: $value")
    }

    private fun backgroundLabel(): Component {
        val bg = RankedState.profile?.background ?: "default"
        return Component.literal("§fCard: ${Backgrounds.label(bg)}")
    }

    private fun rowLabel(t: Toggle): Component {
        val state = if (t.get()) "§aON" else "§7OFF"
        return Component.literal("§f${t.label}: $state")
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        val centerX = width / 2
        g.centeredText(font, "§lOPTIONS", centerX, top - 24, Ui.ACCENT)

        // Hover hint for the toggle under the cursor.
        toggles.firstOrNull { t ->
            val b = t.button ?: return@firstOrNull false
            mouseX >= b.x && mouseX < b.x + b.width && mouseY >= b.y && mouseY < b.y + b.height
        }?.let { g.centeredText(font, "§7${it.hint}", centerX, height - 40, Ui.TEXT_DIM) }

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    override fun onClose() {
        Config.save()
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val ROW = 24

        /** Bottom edge the flowed block must clear: the hover-hint line at
         *  `height - 40` and the Done bar at `height - 28` below it. */
        const val BOTTOM_STRIP = 50

        /** Leaves room for the "OPTIONS" title, drawn 24px above the block. */
        const val MIN_TOP = 26
    }
}
