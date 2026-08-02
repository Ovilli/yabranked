package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.PartyMember
import dev.yabranked.proto.PartyOptions
import dev.yabranked.proto.PartyView
import dev.yabranked.proto.PresenceState
import dev.yabranked.proto.TeamAssignment
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Party management: who is in it, what the leader has chosen, and the controls
 * for both.
 *
 * Every control here is a request to the server — nothing is applied locally in
 * anticipation. The leader-only controls are still *drawn* for members, greyed,
 * so a member can see what the leader is deciding rather than facing a screen
 * that silently has fewer options on it.
 */
class PartyScreen(
    private val parent: Screen?,
) : ScaledScreen(Component.literal("Party")) {

    private var openedAt = 0L

    /** Member rows drawn this frame, so a click can find the player under it. */
    private val memberHits = mutableListOf<Triple<Int, Int, PartyMember>>()

    /** Which member's row is expanded into kick/promote/team controls. */
    private var expanded: String? = null

    /** Fingerprint of the state the widget layout depends on. */
    private var lastSignature = Int.MIN_VALUE

    private fun signature(): Int {
        val party = RankedState.party
        var h = party?.members?.size ?: -1
        h = h * 31 + (party?.leader?.hashCode() ?: 0)
        h = h * 31 + (party?.options?.hashCode() ?: 0)
        h = h * 31 + (if (party?.queued == true) 1 else 0)
        h = h * 31 + (if (RankedState.isPartyLeader) 1 else 0)
        // The start button's enabled state is the server's verdict, so a change
        // in it has to rebuild the row.
        h = h * 31 + (party?.startBlockedReason?.hashCode() ?: 0)
        return h
    }

    private val listTop get() = 34

    override fun layout() {
        if (RankedState.isAuthenticated) RankedParty.connect()
        lastSignature = signature()
        val centerX = width / 2
        val party = RankedState.party
        // Rows flow *up* from the bottom bar, one ROW_GAP apart, and the bottom
        // bar at `height - 28` is nobody else's. Getting this arithmetic wrong
        // is not a cosmetic bug: the last widget added wins the click, so a Back
        // button sharing a row with Start silently ate every press of it.
        val rows = if (party == null) 1 else 4
        var y = height - 28 - rows * ROW_GAP

        if (party == null) {
            addRenderableWidget(
                RankedButton(centerX - 100, y, 200, 20, Component.literal("Create a party")) {
                    RankedParty.create()
                }
            )
            y += ROW_GAP
        } else {
            val leader = RankedState.isPartyLeader
            val locked = party.queued

            addRenderableWidget(
                RankedButton(centerX - 100, y, 98, 20, Component.literal("Invite a friend"), Ui.ICON_FRIENDS) {
                    minecraft.setScreenAndShow(FriendsScreen(this))
                }
            ).active = !locked

            addRenderableWidget(
                RankedButton(centerX + 2, y, 98, 20, Component.literal("Mode: ${party.options.format.displayName}")) {
                    minecraft.setScreenAndShow(ModeSelectScreen(this))
                }
            ).active = leader && !locked
            y += ROW_GAP

            // The three leader choices the party spec calls out: one world or
            // one per side, one card or one per side, and how teams get made.
            addRenderableWidget(
                RankedButton(centerX - 100, y, 64, 20, Component.literal(if (party.options.sharedWorld) "One world" else "Split worlds")) {
                    push(party) { it.copy(sharedWorld = !it.sharedWorld) }
                }
            ).active = leader && !locked

            addRenderableWidget(
                RankedButton(centerX - 32, y, 64, 20, Component.literal(if (party.options.sharedSeed) "One card" else "Split cards")) {
                    push(party) { it.copy(sharedSeed = !it.sharedSeed) }
                }
            ).active = leader && !locked

            addRenderableWidget(
                RankedButton(centerX + 36, y, 64, 20, Component.literal(teamsLabel(party.options.teamAssignment))) {
                    push(party) { it.copy(teamAssignment = nextAssignment(it.teamAssignment)) }
                }
            ).active = leader && !locked && party.options.format.partyOnly
            y += ROW_GAP

            addRenderableWidget(
                RankedButton(centerX - 100, y, 200, 20, Component.literal(if (party.options.ranked) "Rated: on" else "Rated: off")) {
                    push(party) { it.copy(ranked = !it.ranked) }
                }
            ).active = leader && !locked && party.options.format.ranked
            y += ROW_GAP

            // The whole point of the screen: a party that cannot be started is
            // just a chat room. Party-only formats are created outright; every
            // other format goes into matchmaking as a single ticket.
            addRenderableWidget(
                RankedButton(centerX - 100, y, 98, 20, Component.literal(startLabel(party)), Ui.ICON_PLAY) {
                    startPressed(party)
                }
            ).active = leader && startEnabled(party)

            addRenderableWidget(
                RankedButton(centerX + 2, y, 98, 20, Component.literal(if (leader) "§cDisband" else "§cLeave party")) {
                    RankedParty.leave()
                    expanded = null
                }
            ).active = !locked
            y += ROW_GAP
        }

        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
    }

    private fun startLabel(party: PartyView): String = when {
        party.queued -> "§cCancel"
        party.options.format.partyOnly -> "Start match"
        else -> "Find a match"
    }

    /**
     * A queued party can only be pulled back out of the open queue.
     *
     * A party-only match is provisioned the moment it is started — there is no
     * ticket left to withdraw, so the button goes dead until the match lands or
     * the backend gives up on it.
     */
    private fun startEnabled(party: PartyView): Boolean =
        if (party.queued) !party.options.format.partyOnly
        else party.startBlockedReason == null

    private fun startPressed(party: PartyView) {
        if (!RankedState.isPartyLeader) return
        val format = party.options.format
        RankedState.selectedFormat = format
        when {
            party.queued -> RankedQueue.leave()
            // Party against itself: both sides are already here, so there is
            // nothing to match against and the backend creates the record now.
            format.partyOnly -> RankedParty.startMatch()
            // Otherwise the party is one ticket in the normal queue, and only
            // the leader's socket carries it.
            else -> RankedQueue.join(format, asParty = true)
        }
        Sfx.select()
    }

    override fun tick() {
        // An invite that arrives while this screen is open gets the same popup
        // it would get on the ranked menu.
        RankedState.partyInvite?.let { RankedParty.promptForInvite(it, parent = this) }
        val sig = signature()
        if (sig != lastSignature) {
            lastSignature = sig
            rebuildWidgets()
        }
    }

    private fun push(party: PartyView, edit: (PartyOptions) -> PartyOptions) {
        if (!RankedState.isPartyLeader) return
        RankedParty.setOptions(edit(party.options))
    }

    private fun teamsLabel(assignment: TeamAssignment) = when (assignment) {
        TeamAssignment.BALANCED -> "Balanced"
        TeamAssignment.RANDOM -> "Random"
        TeamAssignment.MANUAL -> "Leader"
    }

    private fun nextAssignment(current: TeamAssignment): TeamAssignment {
        val all = TeamAssignment.entries
        return all[(all.indexOf(current) + 1) % all.size]
    }

    // --- interaction ---

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        // Expanded-row actions sit on top of the row that opened them, so they
        // are tested first or the click would just collapse the row.
        for (hit in actionHits) {
            if (event.x() >= hit.x && event.x() < hit.x + hit.w &&
                event.y() >= hit.y && event.y() < hit.y + hit.h
            ) {
                hit.run()
                expanded = null
                Sfx.select()
                rebuildWidgets()
                return true
            }
        }
        for ((top, bottom, member) in memberHits) {
            if (event.y() >= top && event.y() < bottom) {
                onMemberClicked(member)
                return true
            }
        }
        return super.onMouseClicked(event, doubled)
    }

    private fun onMemberClicked(member: PartyMember) {
        val party = RankedState.party ?: return
        val self = RankedState.profile?.uuid
        if (member.player.uuid == self) {
            // Your own row is your ready flag — the one control every member has.
            RankedParty.setReady(!member.ready)
            Sfx.tick()
            return
        }
        if (!RankedState.isPartyLeader || party.queued) {
            Sfx.tick()
            return
        }
        expanded = if (expanded == member.player.uuid) null else member.player.uuid
        Sfx.tick()
        rebuildWidgets()
    }

    // --- rendering ---

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        // Both hit lists are rebuilt every frame from what was actually drawn,
        // so a row the server removed cannot be clicked one frame later.
        memberHits.clear()
        actionHits.clear()

        val centerX = width / 2
        Ui.title(g, font, centerX, "§lPARTY")

        val party = RankedState.party
        val panelWidth = (width - 60).coerceIn(200, 380)
        val left = (width - panelWidth) / 2

        if (party == null) {
            Ui.messageCard(
                g, font, centerX, listTop + 20,
                "You are not in a party. Create one, then invite a friend.",
                width = panelWidth,
                height = 44,
            )
            // Party actions all travel over one socket; when it is down nothing
            // here works and every button looks merely unresponsive. Say so.
            if (!RankedParty.isConnected) {
                g.centeredText(font, "§eNot connected to the party service…", centerX, listTop + 68, Ui.ACCENT)
            }
            if (openedAt == 0L) openedAt = System.currentTimeMillis()
        // The notice stack is drawn once, for every screen, by
            // YabRankedClient — not here, or two screens stacked on one another
            // would each paint their own copy of it.
            Ui.fadeIn(g, width, height, openedAt)
            return
        }

        var y = listTop
        party.members.forEach { member ->
            drawMember(g, member, left, y, panelWidth, mouseX, mouseY)
            memberHits += Triple(y, y + ROW - 2, member)
            y += ROW
            if (expanded == member.player.uuid) {
                drawMemberActions(g, member, left, y, panelWidth, mouseX, mouseY)
                y += ROW
            }
        }

        party.pendingInvites.forEach { invited ->
            Ui.row(g, left, y, panelWidth, ROW - 2)
            PlayerHeads.draw(g, left + 6, y + 5, 18, invited.uuid, invited.name, Ui.TEXT_FAINT)
            g.text(font, Ui.fit(font, invited.name, panelWidth - 100), left + 30, y + 4, Ui.TEXT_DIM)
            Ui.textRight(g, font, "invited", left + panelWidth - 8, y + 8, Ui.TEXT_FAINT)
            y += ROW
        }

        // The one line that says why the start button is dead, phrased by the
        // server so the two can never disagree. Pinned directly above the
        // controls rather than trailing the roster: a full party would otherwise
        // push it down behind them, hiding the only explanation there is.
        val status = when {
            party.queued -> "§eSearching for an opponent…"
            party.startBlockedReason != null -> "§e${party.startBlockedReason}"
            party.options.ranked -> "§aReady — this match will count"
            else -> "§7Ready — this match will not count"
        }
        val controlsTop = height - 28 - 4 * ROW_GAP
        g.centeredText(font, Ui.fit(font, status, width - 20), centerX, minOf(y + 6, controlsTop - 12), Ui.WHITE)

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        // The notice stack is drawn once, for every screen, by
        // YabRankedClient — not here, or a screen stacked on another would
        // paint a second copy of it.
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun drawMember(
        g: GuiGraphicsExtractor,
        member: PartyMember,
        left: Int,
        y: Int,
        w: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        Ui.row(g, left, y, w, ROW - 2)
        val hovered = mouseX >= left && mouseX < left + w && mouseY >= y && mouseY < y + ROW - 2
        if (hovered) g.fill(left, y, left + w, y + ROW - 2, Ui.HOVER)

        val accent = when {
            member.leader -> Ui.ACCENT
            member.presence == PresenceState.OFFLINE -> Ui.TEXT_FAINT
            else -> member.tier?.let(Ui::tierColor) ?: Ui.TEXT_SOFT
        }
        PlayerHeads.draw(g, left + 6, y + 5, 18, member.player.uuid, member.player.name, accent)
        g.text(font, Ui.fit(font, member.player.name, w - 130), left + 30, y + 4, Ui.WHITE)

        val subtitle = buildList {
            if (member.leader) add("leader")
            member.tier?.let { add(it) }
            member.rating?.let { add("$it MMR") }
            member.team?.let { add("team ${'A' + it}") }
        }.joinToString(" · ")
        g.text(font, Ui.fit(font, subtitle, w - 130), left + 30, y + 15, Ui.TEXT_DIM)

        val right = left + w - 8
        if (member.ready) Ui.textRight(g, font, "ready", right, y + 8, Ui.WIN)
        else if (member.player.uuid == RankedState.profile?.uuid) {
            Ui.textRight(g, font, "click when ready", right, y + 8, Ui.TEXT_SOFT)
        }
    }

    private fun drawMemberActions(
        g: GuiGraphicsExtractor,
        member: PartyMember,
        left: Int,
        y: Int,
        w: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        // Inline actions rather than a popup: the row is already the subject, and
        // a popup over a list that the server can rewrite at any moment is a way
        // to click "kick" on somebody who just left.
        val party = RankedState.party ?: return
        val manual = party.options.teamAssignment == TeamAssignment.MANUAL &&
            party.options.format.partyOnly
        val actions = buildList {
            add("Promote" to { RankedParty.promote(member.player.uuid) })
            add("Kick" to { RankedParty.kick(member.player.uuid) })
            if (manual) {
                add("Team A" to { RankedParty.setTeam(member.player.uuid, 0) })
                add("Team B" to { RankedParty.setTeam(member.player.uuid, 1) })
            }
        }
        val cell = w / actions.size
        actions.forEachIndexed { index, (label, run) ->
            val x = left + index * cell
            Ui.row(g, x, y, cell - 2, ROW - 4)
            val hovered = mouseX >= x && mouseX < x + cell - 2 && mouseY >= y && mouseY < y + ROW - 4
            if (hovered) g.fill(x, y, x + cell - 2, y + ROW - 4, Ui.HOVER)
            g.centeredText(font, label, x + cell / 2, y + 6, if (hovered) Ui.WHITE else Ui.TEXT_SOFT)
            memberHitAction(x, y, cell - 2, run)
        }
    }

    /** Action rectangles for the expanded row, checked before the member rows. */
    private val actionHits = mutableListOf<Quad>()

    private data class Quad(val x: Int, val y: Int, val w: Int, val h: Int, val run: () -> Unit)

    private fun memberHitAction(x: Int, y: Int, w: Int, run: () -> Unit) {
        actionHits += Quad(x, y, w, ROW - 4, run)
    }

    override fun onClose() {
        // Pure navigation. Back once disbanded the party on the way out, which
        // made the screen a trap: there was no way to look at the main menu
        // without destroying the party first. Leaving is the explicit red
        // button, and only that.
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val ROW = 30

        /** Vertical pitch of the control rows at the bottom of the screen. */
        const val ROW_GAP = 24
    }
}
