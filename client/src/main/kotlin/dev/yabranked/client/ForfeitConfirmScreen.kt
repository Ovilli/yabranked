package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Conceding is destructive and rating-affecting, so it is confirmed rather
 * than fired straight from the ranked menu.
 */
class ForfeitConfirmScreen(
    private val parent: Screen?,
    private val opponentName: String,
) : ScaledScreen(Component.literal("Forfeit Match")) {

    /**
     * Whether conceding costs rating. Read off the live match rather than
     * assumed: a casual or party game is still forfeitable, and telling its
     * players it will move their MMR is simply false.
     */
    private val rated: Boolean get() = RankedState.activeMatch?.format?.ranked ?: true

    private val stakes: String
        get() = if (rated) "This counts as a loss and affects your rating."
        else "This counts as a loss. Casual match — your rating is unaffected."

    /**
     * Ticks the Forfeit button stays dead after this screen opens.
     *
     * On the pause menu the button that opens this screen sits exactly where
     * vanilla's "Disconnect" / "Save and Quit to Title" was, so it is reached by
     * muscle memory — and a second click landing where the mouse already is
     * conceded the match before the player had read a word of it. A ranked match
     * is twenty minutes of someone's evening; a second of nothing happening is
     * a cheap price for not throwing it away by reflex.
     */
    private val armAfter = 30

    private var ticks = 0
    private var forfeitButton: RankedButton? = null

    /** Seconds still to wait, rounded up; 0 once the button is live. */
    private val armingLeft: Int get() = ((armAfter - ticks) + 19) / 20

    private val armed: Boolean get() = ticks >= armAfter

    override fun layout() {
        val centerX = width / 2
        forfeitButton = addRenderableWidget(
            RankedButton(centerX - 100, height / 2 + 10, 200, 20, forfeitLabel()) {
                // Guarded as well as greyed: `active` stops the click, but a
                // keyboard-activated widget must not find a different answer here.
                if (armed) forfeit() else Sfx.tick()
            }
        ).also { it.active = armed }.tip("Concede the match. $stakes")
        addRenderableWidget(
            RankedButton(centerX - 100, height / 2 + 34, 200, 20, Component.literal("Keep playing"), Ui.ICON_BACK) { onClose() }
        ).tip("Close this and return to the match")
    }

    private fun forfeitLabel(): Component = Component.literal(
        if (armed) "§cForfeit — $opponentName wins" else "§7Forfeit — ${armingLeft}s"
    )

    override fun tick() {
        super.tick()
        if (armed) return
        ticks++
        forfeitButton?.let {
            it.message = forfeitLabel()
            it.active = armed
        }
    }

    /**
     * The stakes are drawn text, and this is the one ranked screen where acting
     * without them is unrecoverable — so the narrator gets them too.
     */
    override fun updateNarrationState(output: NarrationElementOutput) {
        super.updateNarrationState(output)
        output.add(NarratedElementType.HINT, "$stakes $opponentName will be awarded the win.")
    }

    private fun forfeit() {
        // Two channels, because neither one covers both cases.
        //
        // The match server's command is the nicer end: it announces the
        // concession in chat and lets the agent finish the game properly. But it
        // only exists while connected to that server, and this screen is
        // reachable from the ranked menu — where `player` is null and pressing
        // Forfeit used to do precisely nothing, silently, leaving the opponent
        // to wait out the abandon timer.
        //
        // So the backend is told as well. Whichever lands first settles the
        // match; the second is answered with "already over" and discarded.
        val inMatch = minecraft.level != null
        if (inMatch) minecraft.player?.connection?.sendCommand("forfeit")

        val match = RankedState.activeMatch
        val backend = RankedState.backend
        if (match != null && backend != null) {
            YabRankedClient.workers.execute {
                val error = backend.forfeitMatch(match.matchId)
                minecraft.execute {
                    // "Already over" is not a failure to report: the agent got
                    // there first, which is the ordinary outcome of the race.
                    if (error != null) RankedNotice.error(error, title = "Forfeit")
                    // In a match, the container is about to drop us and the
                    // disconnect handler owns the wind-down. From the menus
                    // nothing else ever will, so do it here — otherwise the
                    // client keeps believing it is in a match it just conceded.
                    if (!inMatch && RankedState.activeMatch?.matchId == match.matchId) {
                        YabRankedClient.matchEnded(minecraft, match, "forfeited from the menus")
                    }
                }
            }
        }
        onClose()
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        val centerX = width / 2
        g.centeredText(font, "§lFORFEIT MATCH?", centerX, height / 2 - 40, Ui.LOSS)
        g.centeredText(font, "§7$stakes", centerX, height / 2 - 22, Ui.TEXT_DIM)
        g.centeredText(font, "§7$opponentName will be awarded the win.", centerX, height / 2 - 10, Ui.TEXT_DIM)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }
}
