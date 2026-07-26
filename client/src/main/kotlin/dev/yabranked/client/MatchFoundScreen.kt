package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.narration.NarratedElementType
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * The reveal between "match found" and joining the server: who you drew, how
 * you compare, and a countdown. Connecting instantly gave players no chance to
 * register any of it.
 */
class MatchFoundScreen(
    private val parent: Screen?,
    private val match: QueueServerMessage.MatchFound,
    // The title is also the narration message, so it names the opponent rather
    // than saying "Match Found" to someone who cannot see who they drew.
) : Screen(Component.literal("Match found against ${match.opponent.name}")) {

    private var ticksLeft = COUNTDOWN_TICKS
    private var connecting = false
    private var versus: VersusRecord? = null
    private var lastBeepSecond = -1

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    override fun init() {
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 52, 200, 20, Component.literal("Join now")) { connect() }
        ).setTooltip(Tooltip.create(Component.literal("Skip the countdown and connect to the match server now")))

        minecraft.soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f)
        )
        // Silent toast: RankedQueue already narrated the match the moment it
        // arrived, which is earlier and says the same thing.
        RankedToast.show("Match found", "vs ${match.opponent.name} · ${match.opponentTier}", narrate = false)

        val backend = RankedState.backend
        val self = RankedState.profile
        if (backend != null && self != null && versus == null) {
            val minecraft = this.minecraft
            YabRankedClient.workers.execute {
                val record = backend.fetchVersus(self.uuid, match.opponent.uuid)
                minecraft.execute { versus = record }
            }
        }
    }

    override fun tick() {
        if (connecting) return

        val second = (ticksLeft + 19) / 20
        if (second != lastBeepSecond && second in 1..3) {
            lastBeepSecond = second
            minecraft.soundManager.play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.4f)
            )
        }

        if (ticksLeft-- <= 0) connect()
    }

    private fun connect() {
        if (connecting) return
        connecting = true

        val address = ServerAddress.parseString(match.serverAddress)
        val serverData = ServerData(
            "YAB Ranked vs ${match.opponent.name}",
            match.serverAddress,
            ServerData.Type.OTHER,
        )
        // Parent must NOT be this screen: when the match server disconnects at
        // game end, vanilla's disconnect chain returns to the parent. If that were
        // this MatchFoundScreen, its countdown (already ≤0) would immediately
        // re-fire connect() against the now-dead server and strand the player on
        // "Join now". A fresh TitleScreen is a safe fallback; the mod's own
        // disconnect handler swaps in the result screen over it anyway.
        ConnectScreen.startConnecting(TitleScreen(), Minecraft.getInstance(), address, serverData, false, null)
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        val self = RankedState.profile

        g.centeredText(font, "§lMATCH FOUND", centerX, 26, Ui.ACCENT)

        val top = 52
        val cardWidth = 118
        val gap = 16
        val leftX = centerX - gap / 2 - cardWidth
        val rightX = centerX + gap / 2

        if (self != null) {
            playerCard(g, leftX, top, cardWidth, self.uuid, self.name, self.tier, self.rating, self.country, isSelf = true)
        }
        playerCard(
            g, rightX, top, cardWidth,
            match.opponent.uuid, match.opponent.name, match.opponentTier, match.opponentRating,
            match.opponent.country,
            isSelf = false,
        )

        val emblemY = top + CARD_HEIGHT / 2 - 14
        Ui.vsEmblem(g, centerX, emblemY, size = 28)

        // rating gap tells you what kind of match to expect — suppressed if
        // either rating is hidden, since it would leak the concealed number
        if (self != null && match.opponentRating > 0 && !RankedState.hideElo && !RankedState.hideOpponentElo) {
            val diff = match.opponentRating - self.rating
            val line = when {
                diff > 75 -> "Opponent favoured by +$diff MMR"
                diff < -75 -> "You are favoured by +${-diff} MMR"
                else -> "Evenly matched — ${kotlin.math.abs(diff)} MMR apart"
            }
            g.centeredText(font, line, centerX, top + CARD_HEIGHT + 12, Ui.TEXT_DIM)
        }

        versus?.let { record ->
            val line = if (record.played == 0) {
                "First time facing this opponent"
            } else {
                "Head-to-head: ${record.wins}W · ${record.losses}L" +
                    if (record.draws > 0) " · ${record.draws}D" else ""
            }
            g.centeredText(font, line, centerX, top + CARD_HEIGHT + 24, Ui.TEXT_DIM)
        }

        val seconds = maxOf(0, (ticksLeft + 19) / 20)
        val countdown = if (connecting) "Connecting…" else "Joining in $seconds…"
        // Anchor the countdown just below the head-to-head strip rather than to
        // the screen bottom, so it can't ride up into those lines on a short
        // screen; keep it clear of the bottom "Join now" button either way.
        val countdownY = minOf(top + CARD_HEIGHT + 40, height - 68)
        g.centeredText(font, countdown, centerX, countdownY, Ui.TEXT_DIM)

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun playerCard(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        cardWidth: Int,
        uuid: String,
        name: String,
        tier: String,
        rating: Int,
        country: String?,
        isSelf: Boolean,
    ) {
        val tierColor = Ui.tierColor(tier)
        val centerX = x + cardWidth / 2
        Ui.panel(g, x, y, cardWidth, CARD_HEIGHT)
        // Banner behind the card, matching the profile card treatment.
        Ui.drawUserBackground(g, x + 3, y + 3, cardWidth - 6, CARD_HEIGHT - 6, null)
        Ui.accentBar(g, x, y, CARD_HEIGHT, tierColor)

        g.centeredText(font, if (isSelf) "§7YOU" else "§7OPPONENT", centerX, y + 6, Ui.TEXT_FAINT)
        Ui.slot(g, centerX - HEAD / 2 - 3, y + 14, HEAD + 6)
        PlayerHeads.draw(g, centerX - HEAD / 2, y + 17, HEAD, uuid, name, tierColor)
        val nameY = y + 17 + HEAD + 5
        if (RankedState.showFlags && (!isSelf || !RankedState.hideOwnFlag)) {
            val w = font.width(name)
            val nameLeft = centerX - w / 2
            Ui.flagIcon(g, nameLeft - 12, nameY, country, 9)
        }
        g.centeredText(font, name, centerX, nameY, if (isSelf) Ui.ACCENT else Ui.WHITE)
        Ui.rankBadge(g, centerX - 8, y + 17 + HEAD + 14, tier)
        g.centeredText(font, tier, centerX, y + 17 + HEAD + 34, tierColor)
        // rating <= 0 on the opponent means the server redacted it (they hide
        // their MMR); a real rating never sits at zero. Honour that on top of the
        // viewer's own "hide opponent MMR" display preference.
        val hideRating = if (isSelf) RankedState.hideElo else (RankedState.hideOpponentElo || rating <= 0)
        val ratingText = if (hideRating) "§7MMR hidden" else "$rating MMR"
        g.centeredText(font, ratingText, centerX, y + 17 + HEAD + 46, Ui.TEXT_DIM)
    }

    /**
     * The whole reveal is drawn text with no widget behind it, so the narrator
     * would otherwise get the title and a lone "Join now" button.
     */
    override fun updateNarrationState(output: NarrationElementOutput) {
        super.updateNarrationState(output)

        // Mirrors the cards, including the redactions: a hidden rating must stay
        // hidden when it is spoken rather than drawn.
        val rating = if (RankedState.hideOpponentElo || match.opponentRating <= 0) {
            "MMR hidden"
        } else {
            "${match.opponentRating} MMR"
        }
        val head = versus?.let { record ->
            if (record.played == 0) " First time facing this opponent."
            else " Head to head, ${record.wins} wins, ${record.losses} losses, ${record.draws} draws."
        } ?: ""
        output.add(
            NarratedElementType.HINT,
            "Opponent rank ${match.opponentTier}, $rating.$head Connecting automatically when the countdown ends.",
        )
    }

    /** Escape should not strand the player outside a match that is already live. */
    override fun shouldCloseOnEsc(): Boolean = false

    override fun onClose() {
        // no-op: the countdown owns the transition
    }

    private companion object {
        const val COUNTDOWN_TICKS = 5 * 20
        const val CARD_HEIGHT = 108
        const val HEAD = 32
    }
}
