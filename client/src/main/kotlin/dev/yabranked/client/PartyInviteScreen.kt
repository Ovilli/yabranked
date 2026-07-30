package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.PartyInviteView
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * "X invited you to their party" — accept or decline.
 *
 * A screen rather than a toast because it needs an answer: a toast can be
 * missed, and an invite that expires unanswered looks to the inviter like being
 * ignored. It closes itself the moment the invite stops being answerable —
 * accepted, declined, expired, or withdrawn when the party disbanded — so it can
 * never sit there offering a button that would now be refused.
 *
 * [RankedParty] only opens this when the player is actually in the menus; an
 * invite arriving mid-match is left as a toast and as the buttons on the ranked
 * screen, because taking the screen away from someone who is playing is worse
 * than a missed invite.
 */
class PartyInviteScreen(
    private val parent: Screen?,
    private val invite: PartyInviteView,
) : Screen(Component.literal("Party invite from ${invite.from.name}")) {

    private var openedAt = 0L
    private var answered = false

    private fun secondsLeft(): Long {
        if (invite.expiresAt <= 0) return Long.MAX_VALUE
        return ((invite.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
    }

    override fun init() {
        val centerX = width / 2
        val y = height / 2 + 30

        addRenderableWidget(
            RankedButton(centerX - 102, y, 100, 20, Component.literal("§aAccept")) { accept() }
        )
        addRenderableWidget(
            RankedButton(centerX + 2, y, 100, 20, Component.literal("§cDecline")) { decline() }
        )
    }

    private fun accept() {
        answered = true
        RankedParty.accept(invite.partyId)
        RankedState.partyInvite = null
        Sfx.select()
        // Straight to the party rather than back to wherever they were: they
        // just said yes, and the party screen is what they said yes to.
        minecraft.setScreenAndShow(PartyScreen(parent))
    }

    private fun decline() {
        answered = true
        RankedParty.decline(invite.partyId)
        RankedState.partyInvite = null
        Sfx.tick()
        onClose()
    }

    override fun tick() {
        if (answered) return
        // The invite is gone the moment it is no longer this one — accepted
        // elsewhere, withdrawn, or replaced by a newer invite.
        if (RankedState.partyInvite?.partyId != invite.partyId) {
            answered = true
            onClose()
            return
        }
        if (secondsLeft() <= 0) {
            answered = true
            RankedState.partyInvite = null
            RankedToast.show("Party invite", "${invite.from.name}'s invite expired")
            onClose()
        }
    }

    /** Escape declines rather than dismissing: silence would be misread. */
    override fun onClose() {
        if (!answered) {
            decline()
            return
        }
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        val panelWidth = 240
        val panelHeight = 110
        val left = centerX - panelWidth / 2
        val top = height / 2 - 78

        Ui.panel(g, left, top, panelWidth, panelHeight)
        Ui.accentBar(g, left, top, panelHeight, Ui.ACCENT)

        g.centeredText(font, "§lPARTY INVITE", centerX, top + 8, Ui.ACCENT)

        Ui.slot(g, centerX - 15, top + 22, 30)
        PlayerHeads.draw(g, centerX - 12, top + 25, 24, invite.from.uuid, invite.from.name, Ui.ACCENT)

        g.centeredText(font, invite.from.name, centerX, top + 56, Ui.WHITE)
        g.centeredText(font, "wants you in their party", centerX, top + 68, Ui.TEXT_DIM)

        // Who else is already in it — the thing that actually decides the answer.
        if (invite.members.isNotEmpty()) {
            val names = invite.members.joinToString(", ") { it.name }
            g.centeredText(
                font,
                Ui.fit(font, "With: $names", panelWidth - 16),
                centerX, top + 82, Ui.TEXT_FAINT,
            )
        }

        val seconds = secondsLeft()
        if (seconds != Long.MAX_VALUE) {
            g.centeredText(
                font,
                "Expires in ${Ui.duration(seconds)}",
                centerX, top + 94,
                if (seconds <= 15) Ui.LOSS else Ui.TEXT_FAINT,
            )
        }

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }
}
