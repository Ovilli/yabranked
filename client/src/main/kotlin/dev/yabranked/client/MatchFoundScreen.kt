package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.Screen
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
    private val match: WireQueueServerMessage.MatchFound,
) : Screen(Component.literal("Match Found")) {

    private var ticksLeft = COUNTDOWN_TICKS
    private var connecting = false
    private var versus: WireVersusRecord? = null
    private var lastBeepSecond = -1

    override fun init() {
        addRenderableWidget(
            Button.builder(Component.literal("Join now")) { connect() }
                .bounds(width / 2 - 100, height - 52, 200, 20)
                .build()
        )

        minecraft.soundManager.play(
            SimpleSoundInstance.forUI(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f)
        )
        RankedToast.show("Match found", "vs ${match.opponent.name} · ${match.opponentTier}")

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
        ConnectScreen.startConnecting(this, Minecraft.getInstance(), address, serverData, false, null)
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
            playerCard(g, leftX, top, cardWidth, self.uuid, self.name, self.tier, self.rating, isSelf = true)
        }
        playerCard(
            g, rightX, top, cardWidth,
            match.opponent.uuid, match.opponent.name, match.opponentTier, match.opponentRating,
            isSelf = false,
        )

        Ui.vsEmblem(g, centerX, top + CARD_HEIGHT / 2 - 8)

        // rating gap tells you what kind of match to expect
        if (self != null && match.opponentRating > 0) {
            val diff = match.opponentRating - self.rating
            val line = when {
                diff > 75 -> "§7Opponent is favoured by §c+$diff§7 MMR"
                diff < -75 -> "§7You are favoured by §a+${-diff}§7 MMR"
                else -> "§7Evenly matched — §f${kotlin.math.abs(diff)}§7 MMR apart"
            }
            g.centeredText(font, line, centerX, top + CARD_HEIGHT + 12, Ui.TEXT_DIM)
        }

        versus?.let { record ->
            val line = if (record.played == 0) {
                "§7First time facing this opponent"
            } else {
                "§7Head-to-head: §a${record.wins}W §7- §c${record.losses}L" +
                    if (record.draws > 0) " §7- §8${record.draws}D" else ""
            }
            g.centeredText(font, line, centerX, top + CARD_HEIGHT + 24, Ui.TEXT_DIM)
        }

        val seconds = maxOf(0, (ticksLeft + 19) / 20)
        val countdown = if (connecting) "Connecting…" else "Joining in §f$seconds§7…"
        g.centeredText(font, "§7$countdown", centerX, height - 70, Ui.WHITE)
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
        isSelf: Boolean,
    ) {
        val tierColor = Ui.tierColor(tier)
        val centerX = x + cardWidth / 2
        Ui.panel(g, x, y, cardWidth, CARD_HEIGHT)
        Ui.accentBar(g, x, y, CARD_HEIGHT, tierColor)

        g.centeredText(font, if (isSelf) "§7YOU" else "§7OPPONENT", centerX, y + 6, Ui.TEXT_FAINT)
        PlayerHeads.draw(g, centerX - HEAD / 2, y + 17, HEAD, uuid, name, tierColor)
        g.centeredText(font, name, centerX, y + 17 + HEAD + 5, if (isSelf) Ui.ACCENT else Ui.WHITE)
        Ui.rankBadge(g, centerX - 8, y + 17 + HEAD + 14, tier)
        g.centeredText(font, tier, centerX, y + 17 + HEAD + 34, tierColor)
        g.centeredText(font, "$rating MMR", centerX, y + 17 + HEAD + 46, Ui.TEXT_DIM)
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
