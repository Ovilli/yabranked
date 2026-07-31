package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * The screen title is also its narration message, so it carries the two facts
 * the player came for instead of the generic "Match Result" — this screen opens
 * on its own after a disconnect, not because anyone navigated to it.
 */
private fun resultTitle(entry: MatchHistoryEntry): Component {
    val outcome = when (entry.result) {
        "win" -> "Victory"
        "loss" -> "Defeat"
        "draw" -> "Draw"
        else -> "Match voided"
    }
    val after = entry.ratingAfter
    val movement = when {
        !entry.rated -> "casual match, no rating change"
        after == null -> "no rating change"
        else -> {
            val delta = after - entry.ratingBefore
            if (delta >= 0) "plus $delta MMR" else "minus ${-delta} MMR"
        }
    }
    return Component.literal("$outcome against ${entry.opponent.name}, $movement")
}

/**
 * Shown after leaving a ranked match: the outcome, the rating movement, and a
 * tier promotion if one happened. Previously the result only surfaced if the
 * player happened to reopen the ranked menu.
 */
class MatchResultScreen(
    private val entry: MatchHistoryEntry,
    private val profileBefore: PlayerProfile?,
    private val profileAfter: PlayerProfile,
) : ScaledScreen(resultTitle(entry)) {

    /** Metal-band ordinal of a tier; -1 for Unranked. Division changes within a
     *  band do not count as a promotion/demotion. */
    private fun band(tier: String): Int = when (tier.substringBefore(' ')) {
        "Coal" -> 0; "Iron" -> 1; "Gold" -> 2; "Emerald" -> 3; "Diamond" -> 4; "Netherite" -> 5
        else -> -1
    }

    private val promoted = profileBefore != null && band(profileAfter.tier) > band(profileBefore.tier)
    private val demoted = profileBefore != null && band(profileAfter.tier) < band(profileBefore.tier)

    /** Ticks since open, used to count the rating up rather than snapping. */
    private var ticks = 0

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    /** The "keep this recording" button, relabelled once the save lands. */
    private var keepReplayButton: RankedButton? = null
    private var savingReplay = false

    override fun layout() {
        RankedState.onResultScreen = true
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 52, 200, 20, Component.literal("Queue again (R)"), Ui.ICON_PLAY) { queueAgain() }
        ).tip("Rejoin the queue and return to the ranked menu")
        // Quick actions row above the main button
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 76, 64, 20, Component.literal("History"), Ui.ICON_HISTORY) { openHistory() }
        ).tip("Browse your recent ranked matches")
        addRenderableWidget(
            RankedButton(width / 2 - 32, height - 76, 64, 20, Component.literal("Copy ID")) { copyMatchId() }
        ).tip("Copy this match's ID to the clipboard")
        // Reporting lives here (post-match), not on the main menu: this is the
        // "player you recently played with". Hidden once the match is reported.
        addRenderableWidget(
            RankedButton(width / 2 + 36, height - 76, 64, 20, Component.literal("§cReport"), Ui.ICON_REPORT) { openReport() }
        ).apply {
            active = !RankedState.lastMatchReported
            hoverTip = if (active) "Report ${entry.opponent.name} for misconduct in this match"
                else "You have already reported this match"
        }
        // Only offered for modes that had teammates, and only while the match
        // is still inside the endorsement window — the backend decides both, and
        // answers with an empty prompt otherwise.
        RankedState.lastMatch?.takeIf { it.format.endorsable }?.let { match ->
            addRenderableWidget(
                RankedButton(width / 2 - 100, height - 100, 200, 20, Component.literal("Endorse teammates"), Ui.ICON_FRIENDS) {
                    minecraft.setScreenAndShow(EndorseScreen(this, match.matchId))
                }
            )
        }

        // Every match records a replay and unsaved ones are deleted after the
        // retention window, so the end of the match is the one moment the player
        // is guaranteed to be asked. Saving is one click; watching is the other.
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 124, 96, 20, Component.literal("Save replay"), Ui.ICON_BOARD) {
                saveReplay()
            }
        ).also { keepReplayButton = it }
        addRenderableWidget(
            RankedButton(width / 2 + 4, height - 124, 96, 20, Component.literal("Watch replay"), Ui.ICON_PLAY) {
                minecraft.setScreenAndShow(
                    dev.yabranked.client.replay.ReplayDownloadScreen(this, entry.matchId, "vs ${entry.opponent.name} · ${entry.format.displayName}")
                )
            }
        )

        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Done"), Ui.ICON_BACK) { onClose() }
        ).tip("Close and return to the title screen")

        val sound = when {
            promoted -> SoundEvents.UI_TOAST_CHALLENGE_COMPLETE
            entry.result == "win" -> SoundEvents.PLAYER_LEVELUP
            else -> SoundEvents.UI_TOAST_IN
        }
        minecraft.soundManager.play(SimpleSoundInstance.forUI(sound, 1.0f))
    }

    override fun tick() {
        ticks++
    }

    private fun queueAgain() {
        if (!RankedState.isQueued && RankedState.isAuthenticated) {
            RankedQueue.join()
        }
        val title = TitleScreen()
        minecraft.setScreenAndShow(RankedScreen(title))
    }

    private fun copyMatchId() {
        minecraft.keyboardHandler.setClipboard(entry.matchId)
        RankedToast.showInfo("Copied", "Match ID copied to clipboard")
    }

    private fun openHistory() {
        minecraft.setScreenAndShow(MatchHistoryScreen(this))
    }

    /**
     * Keep this match's recording.
     *
     * The recording is uploaded before the result, so by the time this screen
     * exists it is normally already there. "Normally" is not "always" — a
     * match settled from the menus is settled by the *client*, and the match
     * server is then still finishing its upload — so a "no replay" answer is
     * retried a couple of times before it is believed. Any other error is the
     * backend's verdict and is shown as it came.
     */
    private fun saveReplay() {
        val backend = RankedState.backend ?: return
        if (savingReplay) return
        savingReplay = true
        val minecraft = this.minecraft
        YabRankedClient.workers.execute {
            var error = backend.saveReplay(entry.matchId)
            var attempt = 0
            while (error != null && error.contains("no replay", ignoreCase = true) && attempt < SAVE_RETRIES) {
                attempt++
                Thread.sleep(SAVE_RETRY_MILLIS)
                error = backend.saveReplay(entry.matchId)
            }
            minecraft.execute {
                savingReplay = false
                if (error != null) {
                    RankedNotice.error(error, title = "Replay")
                    Sfx.tick()
                } else {
                    keepReplayButton?.message = Component.literal("§aSaved")
                    keepReplayButton?.active = false
                    RankedNotice.info("Replay saved to your account", title = "Replay")
                    Sfx.select()
                }
            }
        }
    }

    private fun openReport() {
        // Naming the opponent matters in team modes: without it the backend
        // picks the first player of the other side, who need not be the one the
        // reporter meant.
        minecraft.setScreenAndShow(
            ReportScreen(this, entry.matchId, entry.opponent.name, entry.opponent.uuid)
        )
    }

    // Keyboard shortcut (R) is handled via a Fabric KeyMapping in YabRankedClient.

    private fun resultColor() = when (entry.result) {
        "win" -> Ui.WIN
        "loss" -> Ui.LOSS
        "draw" -> Ui.DRAW
        else -> Ui.TEXT_FAINT
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        val headline = when (entry.result) {
            "win" -> "§lVICTORY"
            "loss" -> "§lDEFEAT"
            "draw" -> "§lDRAW"
            else -> "§lMATCH VOIDED"
        }
        // Result icon left of the headline: crown on promotion, else win/loss tick.
        val headlineW = font.width(headline)
        val iconIdx = when {
            promoted -> Ui.ICON_CROWN
            entry.result == "win" -> Ui.ICON_WIN
            entry.result == "loss" -> Ui.ICON_LOSS
            else -> null
        }
        if (iconIdx != null) {
            Ui.icon(g, iconIdx, centerX - headlineW / 2 - 16, 27, 12, resultColor())
        }
        g.centeredText(font, headline, centerX, 28, resultColor())
        val ago = Ui.relativeTime(entry.completedAt)
        val headerLine = "§7vs ${entry.opponent.name} · ${Ui.duration(entry.durationSeconds)}" +
            if (ago.isNotEmpty()) " · $ago" else ""
        g.centeredText(font, headerLine, centerX, 42, Ui.TEXT_DIM)
        if (RankedState.showFlags) {
            val left = centerX - font.width(headerLine) / 2
            val prefixW = font.width("vs ")
            Ui.flagIcon(g, left + prefixW, 40, entry.opponent.country, 8)
        }

        val left = centerX - CARD_WIDTH / 2
        val top = 60
        Ui.panel(g, left, top, CARD_WIDTH, CARD_HEIGHT)
        // Banner behind the card, matching the profile card treatment.
        Ui.drawUserBackground(g, left + 3, top + 3, CARD_WIDTH - 6, CARD_HEIGHT - 6, profileAfter.background)
        Ui.accentBar(g, left, top, CARD_HEIGHT, resultColor())

        val after = entry.ratingAfter
        if (!entry.rated) {
            // A casual game has a rating *before* it like any other, so the card
            // used to render a delta chip against it. Nothing moved, and saying
            // "±0 MMR" to someone who queued casual reads as if it had.
            g.centeredText(font, "§7Casual match", centerX, top + 22, Ui.TEXT_DIM)
            g.centeredText(font, "§8${entry.format.displayName} · MMR unaffected", centerX, top + 34, Ui.TEXT_FAINT)
        } else if (after == null) {
            g.centeredText(font, "§7No rating change", centerX, top + 26, Ui.TEXT_DIM)
        } else {
            val delta = after - entry.ratingBefore
            // count the rating up over ~1s so the change is legible
            val progress = (ticks / 20f).coerceIn(0f, 1f)
            val shown = entry.ratingBefore + (delta * progress).toInt()

            // Hide the absolute rating if the player opted out, but still show
            // the delta chip — the change carries no standings information.
            val shownText = if (RankedState.hideElo) "§7•••" else "$shown"
            g.centeredText(font, shownText, centerX, top + 18, Ui.WHITE)
            g.centeredText(font, "§7MMR", centerX, top + 30, Ui.TEXT_FAINT)
            val deltaText = if (delta >= 0) "▲ +$delta" else "▼ $delta"
            val color = if (delta >= 0) Ui.WIN else Ui.LOSS
            val bg = Ui.alpha(color, 0x33)
            Ui.chip(g, font, centerX, top + 40, deltaText, bg, Ui.WHITE)
        }

        Ui.rankBadge(g, left + 10, top + 20, profileAfter.tier)
        g.text(font, profileAfter.tier, left + 10, top + 40, Ui.tierColor(profileAfter.tier))
        profileAfter.rank?.let {
            Ui.textRight(g, font, "Rank #$it", left + CARD_WIDTH - 10, top + 40, Ui.TEXT_DIM)
        }

        val infoY = top + CARD_HEIGHT + 12
        when {
            promoted && profileBefore != null -> drawRankUp(g, centerX, infoY, profileBefore, profileAfter)
            demoted && profileBefore != null -> drawRankDown(g, centerX, infoY, profileBefore, profileAfter)
            profileAfter.placementMatchesRemaining > 0 -> {
                g.centeredText(
                    font,
                    "§7${profileAfter.placementMatchesRemaining} placement matches remaining",
                    centerX, infoY, Ui.TEXT_DIM,
                )
                drawStreak(g, centerX, infoY + 14)
            }
            else -> drawStreak(g, centerX, infoY)
        }

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    /** Win streak, on a win only. Fed from history by the disconnect poll. */
    private fun drawStreak(g: GuiGraphicsExtractor, centerX: Int, y: Int) {
        if (entry.result == "win" && RankedState.winStreak >= 2) {
            g.centeredText(font, "§6▲ ${RankedState.winStreak} win streak", centerX, y, Ui.WIN)
        }
    }

    /**
     * Promotion flourish: the new crest eases up to full size with a soft glow,
     * then the tier transition reads underneath. Driven by [ticks] so it plays
     * once when the screen opens.
     */
    private fun drawRankUp(g: GuiGraphicsExtractor, centerX: Int, y: Int, before: PlayerProfile, after: PlayerProfile) {
        val prog = (ticks / 10f).coerceIn(0f, 1f)
        val ease = 1f - (1f - prog) * (1f - prog) // easeOutQuad
        val size = (10 + 22 * ease).toInt()
        val crestX = centerX - size / 2

        Ui.softGlow(g, centerX, y + 2, size + 16, size, Ui.ACCENT)
        Ui.rankBadge(g, crestX, y, after.tier, size)
        g.centeredText(font, "§lTIER UP!", centerX, y + size + 4, Ui.ACCENT)
        g.centeredText(font, "${before.tier} → ${after.tier}", centerX, y + size + 16, Ui.TEXT_DIM)
    }

    /** Demotion counterpart: the new (lower) crest, no celebratory glow, red note. */
    private fun drawRankDown(g: GuiGraphicsExtractor, centerX: Int, y: Int, before: PlayerProfile, after: PlayerProfile) {
        Ui.rankBadge(g, centerX - 12, y, after.tier, 24)
        g.centeredText(font, "§lTIER LOST", centerX, y + 28, Ui.LOSS)
        g.centeredText(font, "${before.tier} → ${after.tier}", centerX, y + 40, Ui.TEXT_DIM)
    }

    /**
     * Tier movement, placements and the streak are drawn text with nothing
     * focusable behind them, so they exist for the narrator only if said here.
     */
    override fun updateNarrationState(output: NarrationElementOutput) {
        super.updateNarrationState(output)

        val lines = buildList {
            add("Now ${profileAfter.tier}.")
            if (profileBefore != null && promoted) add("Tier up from ${profileBefore.tier}.")
            if (profileBefore != null && demoted) add("Tier lost, down from ${profileBefore.tier}.")
            if (profileAfter.placementMatchesRemaining > 0) {
                add("${profileAfter.placementMatchesRemaining} placement matches remaining.")
            }
            if (entry.result == "win" && RankedState.winStreak >= 2) {
                add("${RankedState.winStreak} win streak.")
            }
        }
        output.add(NarratedElementType.HINT, lines.joinToString(" "))
    }

    override fun onClose() {
        RankedState.onResultScreen = false
        minecraft.setScreenAndShow(TitleScreen())
    }

    // Keyboard shortcut (R) hint is shown in the label; to wire actual
    // key handling, override keyPressed(KeyEvent) according to the MC API
    // version you target.

    private companion object {
        const val CARD_WIDTH = 200
        const val CARD_HEIGHT = 62

        /** Extra tries for a save that beat the match server's upload. */
        const val SAVE_RETRIES = 3
        const val SAVE_RETRY_MILLIS = 2000L
    }
}
