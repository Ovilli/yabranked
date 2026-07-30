package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.PartyBox
import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class RankedScreen(
    private val parent: Screen?,
) : Screen(Component.literal("YAB Ranked")) {

    private var queueButton: RankedButton? = null
    private var pickerButton: RankedButton? = null

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    /** Where the status/searching line is drawn; set during [init] so it always
     *  sits in the reserved strip under the card and never lands on a button. */
    private var statusY = CARD_TOP + CARD_HEIGHT + STATUS_GAP

    /** Rebuilds the widgets in place; keeps the screen instance and its state. */
    private fun refresh() = rebuildWidgets()

    /** Fingerprint of the state that drives the button layout. When it changes
     *  (e.g. the queue socket connects on a worker thread, a match is found, or
     *  the queue closes), the widgets are rebuilt so labels/enabled-state track
     *  it. Without this, joining the queue left the button reading "Play" because
     *  [RankedQueue.join] sets the socket asynchronously, after refresh() ran. */
    private var lastSignature = Int.MIN_VALUE

    private fun stateSignature(): Int {
        var h = if (RankedState.isAuthenticated) 1 else 0
        h = h * 31 + (if (RankedState.isQueued) 1 else 0)
        h = h * 31 + (if (RankedState.activeMatch != null) 1 else 0)
        h = h * 31 + RankedState.selectedFormat.name.hashCode()
        // The party box and the queue button both change shape with the party,
        // and an invite arriving needs the prompt drawn under the card.
        h = h * 31 + (RankedState.party?.members?.size ?: -1)
        h = h * 31 + (if (RankedState.isPartyLeader) 1 else 0)
        h = h * 31 + (RankedState.partyInvite?.partyId?.hashCode() ?: 0)
        return h
    }

    override fun init() {
        RankedState.onRankedScreen = true
        // Invites and friend requests are server pushes, so the socket has
        // to be open before one arrives — not opened in reaction to one.
        if (RankedState.isAuthenticated) RankedParty.connect()
        lastSignature = stateSignature()
        val centerX = width / 2
        val cardBottom = CARD_TOP + CARD_HEIGHT

        // Reserve a two-line strip directly under the card for the status /
        // searching text, then flow the buttons below it. Deriving every Y from
        // this cursor keeps the layout from colliding at any GUI scale.
        statusY = cardBottom + STATUS_GAP
        var by = statusY + STATUS_STRIP

        // while a match is live the menu's job is the match, not the queue
        val liveMatch = RankedState.activeMatch
        if (liveMatch != null) {
            addRenderableWidget(
                RankedButton(centerX - 100, by, 200, 20, Component.literal("§cForfeit match")) {
                    minecraft.setScreenAndShow(ForfeitConfirmScreen(this, liveMatch.opponent.name))
                }
            )
            by += ROW
            addBackButton(centerX, by)
            return
        }

        if (!RankedState.isAuthenticated) {
            addRenderableWidget(
                RankedButton(centerX - 100, by, 200, 20, Component.literal("Log in with Minecraft")) { login() }
            )
            by += ROW
        } else {
            // the format is locked in once queued, so the picker is disabled
            // rather than removed to keep the button layout from jumping
            pickerButton = RankedButton(centerX - 100, by, 200, 20, formatLabel()) {
                minecraft.setScreenAndShow(ModeSelectScreen(this))
            }
            pickerButton!!.active = !RankedState.isQueued
            addRenderableWidget(pickerButton!!)
            by += ROW

            queueButton = addRenderableWidget(
                RankedButton(centerX - 100, by, 200, 20, queueLabel(), Ui.ICON_PLAY) { toggleQueue() }
            )
            // Enable only when the corresponding action is valid
            queueButton?.active = if (RankedState.isQueued) true else RankedState.isAuthenticated
            by += ROW

            addRenderableWidget(
                RankedButton(centerX - 100, by, 98, 20, Component.literal("Leaderboard"), Ui.ICON_LEADERBOARD) {
                    minecraft.setScreenAndShow(LeaderboardScreen(this))
                }
            )
            addRenderableWidget(
                RankedButton(centerX + 2, by, 98, 20, Component.literal("History (H)"), Ui.ICON_HISTORY) {
                    minecraft.setScreenAndShow(MatchHistoryScreen(this))
                }
            )
            by += ROW

            // Its own way in, not only through a match in History. A downloaded
            // recording is a local file that outlives the backend's memory of the
            // match, so there has to be a door that does not go through the
            // backend at all.
            addRenderableWidget(
                RankedButton(centerX - 100, by, 200, 20, Component.literal("Replays"), Ui.ICON_PLAY) {
                    minecraft.setScreenAndShow(dev.yabranked.client.replay.ReplayLibraryScreen(this))
                }
            )
            by += ROW

            addRenderableWidget(
                RankedButton(centerX - 100, by, 98, 20, Component.literal(friendsLabel()), Ui.ICON_FRIENDS) {
                    minecraft.setScreenAndShow(FriendsScreen(this))
                }
            )
            addRenderableWidget(
                RankedButton(centerX + 2, by, 98, 20, Component.literal(partyLabel()), Ui.ICON_PARTY) {
                    minecraft.setScreenAndShow(PartyScreen(this))
                }
            )
            by += ROW

            // An invite is answered here rather than only on the party screen:
            // it arrives while the player is looking at this one, and burying
            // the accept a screen deep is how invites time out.
            RankedState.partyInvite?.let { invite ->
                addRenderableWidget(
                    RankedButton(centerX - 100, by, 98, 20, Component.literal("Join ${invite.from.name}")) {
                        RankedParty.accept(invite.partyId)
                    }
                )
                addRenderableWidget(
                    RankedButton(centerX + 2, by, 98, 20, Component.literal("Decline")) {
                        RankedParty.decline(invite.partyId)
                        RankedState.partyInvite = null
                        refresh()
                    }
                )
                by += ROW
            }
        }

        addRenderableWidget(
            RankedButton(centerX - 100, by, 200, 20, Component.literal("Options"), Ui.ICON_OPTIONS) {
                minecraft.setScreenAndShow(RankedOptionsScreen(this))
            }
        )
        by += ROW

        addBackButton(centerX, by)
    }

    /** Rebuild when the queue/auth/match state shifts under us, including the
     *  async socket connect that [RankedQueue.join] completes on a worker. */
    override fun tick() {
        val sig = stateSignature()
        if (sig != lastSignature) {
            lastSignature = sig
            refresh()
        }
    }

    /** Pins Back to the bottom when there is room, else places it just below the
     *  flowed content so it can never overlap a button on a short screen. */
    private fun addBackButton(centerX: Int, contentBottom: Int) {
        val y = maxOf(height - 28, contentBottom + 4)
        addRenderableWidget(
            RankedButton(centerX - 100, y, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
    }

    private fun queueLabel(): Component = Component.literal(
        if (RankedState.isQueued) {
            "Leave Queue (L)"
        } else {
            val format = RankedState.selectedFormat
            val verb = if (format.ranked) "Play Ranked" else "Play Casual"
            "$verb ${format.displayName} (J)"
        }
    )

    private fun formatLabel(): Component = Component.literal(
        "Mode: ${RankedState.selectedFormat.displayName}" +
            if (RankedState.selectedFormat.ranked) " (rated)" else " (casual)"
    )

    private fun friendsLabel(): String {
        val pending = RankedState.friendRequests
        return if (pending > 0) "Friends ($pending)" else "Friends"
    }

    private fun partyLabel(): String {
        val party = RankedState.party ?: return "Party"
        return "Party ${party.members.size}/${party.maxSize}"
    }

    private fun login() {
        val minecraft = this.minecraft
        RankedState.statusMessage = "Signing in…"

        val user = minecraft.user
        val sessionService = minecraft.services().sessionService()
        val backend = BackendClient(YabRankedClient.backendUrl, YabRankedClient.modVersion)

        YabRankedClient.workers.execute {
            val result = backend.authenticate(user.name) { serverId ->
                sessionService.joinServer(user.profileId, user.accessToken, serverId)
            }
            minecraft.execute {
                when (result) {
                    is BackendClient.AuthResult.Ok -> {
                        RankedState.backend = backend
                        RankedState.profile = result.session.profile
                        // The server is authoritative for these two prefs; mirror
                        // them into the local toggles so the options screen shows
                        // the real state and doesn't clobber it on next save.
                        RankedState.hideOwnFlag = result.session.profile.hideFlag
                        RankedState.hideElo = result.session.profile.hideRating
                        RankedState.statusMessage = null
                        // The party socket is also the channel invites and
                        // friend requests arrive on, so it opens as soon as
                        // there is a session — not when a party is created.
                        RankedParty.connect()
                        // Populate the win streak shown on the profile card.
                        val uuid = result.session.profile.uuid
                        YabRankedClient.workers.execute {
                            val hist = backend.fetchHistory(uuid, limit = 20).orElse(emptyList())
                            val friends = backend.fetchFriends()
                            minecraft.execute {
                                RankedState.winStreak = result.session.profile.currentStreak
                                    ?: RankedState.currentWinStreak(hist)
                                RankedState.friendRequests =
                                    friends.orElse(FriendListResponse()).incoming.size
                                refresh()
                            }
                        }
                    }
                    is BackendClient.AuthResult.Outdated ->
                        RankedState.statusMessage = "§c${result.message}"
                    is BackendClient.AuthResult.Failed ->
                        RankedState.statusMessage = "§c${result.message}"
                }
                refresh()
            }
        }
    }

    private fun toggleQueue() {
        val party = RankedState.party
        when {
            RankedState.isQueued -> RankedQueue.leave()
            // A party-only format has no queue to join — the party is both
            // sides — so the same button asks the backend to create the match
            // outright. Doing it here as well as on the party screen keeps the
            // main button honest whatever mode is selected.
            RankedState.selectedFormat.partyOnly -> when {
                party == null -> RankedState.statusMessage = "§eCreate a party first"
                !RankedState.isPartyLeader ->
                    RankedState.statusMessage = "§eOnly the party leader starts the match"
                party.startBlockedReason != null ->
                    RankedState.statusMessage = "§e${party.startBlockedReason}"
                else -> RankedParty.startMatch()
            }
            else -> RankedQueue.join(asParty = RankedState.queueAsParty)
        }
        // rebuild so the queue label and the picker's enabled state track the
        // new queue status (the socket itself connects asynchronously)
        refresh()
    }

    // --- rendering ---

    /** Backdrop draws in the background pass so the buttons stay on top of it. */
    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        Ui.header(g, centerX - 110, 10, 220, 30)
        g.centeredText(font, "§lYAB RANKED", centerX, 16, Ui.ACCENT)
        g.centeredText(font, RankedState.selectedFormat.displayName, centerX, 28, Ui.TEXT_FAINT)

        val profile = RankedState.profile
        if (profile == null) {
            if (!RankedState.isAuthenticated) drawSignedOutCard(g, centerX) else drawLoadingCard(g, centerX)
        } else {
            drawProfileCard(g, centerX, profile)
        }

        drawPartyBox(g, mouseX, mouseY)

        drawStatusLine(g, centerX)

        drawDisabledHints(g, mouseX, mouseY)

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        // The notice stack is drawn once, for every screen, by
        // YabRankedClient — not here, or a screen stacked on another would
        // paint a second copy of it.
        Ui.fadeIn(g, width, height, openedAt)
    }

    // Keyboard shortcuts are implemented via Fabric KeyMappings in YabRankedClient.

    /** Top-left party strip: heads of everyone in it, plus the invite slot. */
    private fun drawPartyBox(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!RankedState.isAuthenticated) return
        val party = RankedState.party
        // With no party there is only the "+" — still worth drawing, since it is
        // the discoverable way in.
        val hovered = PartyBox.slotAt(party, PARTY_BOX_X, PARTY_BOX_Y, mouseX, mouseY)
        PartyBox.draw(g, font, PARTY_BOX_X, PARTY_BOX_Y, party, hovered)
        if (hovered != null) {
            val hint = when {
                hovered == PartyBox.INVITE_SLOT -> "Invite a friend"
                else -> party?.members?.getOrNull(hovered)?.player?.name ?: ""
            }
            if (hint.isNotEmpty()) drawTooltip(g, mouseX, mouseY, hint)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (RankedState.isAuthenticated) {
            val party = RankedState.party
            val slot = PartyBox.slotAt(party, PARTY_BOX_X, PARTY_BOX_Y, event.x().toInt(), event.y().toInt())
            if (slot != null) {
                // The "+" goes to the friends list (the only place a valid invite
                // target can come from); a member's head goes to the party screen.
                if (slot == PartyBox.INVITE_SLOT) {
                    if (party == null) RankedParty.create()
                    minecraft.setScreenAndShow(FriendsScreen(this))
                } else {
                    minecraft.setScreenAndShow(PartyScreen(this))
                }
                Sfx.select()
                return true
            }
        }
        return super.mouseClicked(event, doubled)
    }

    private fun drawSignedOutCard(g: GuiGraphicsExtractor, centerX: Int) {
        val left = centerX - CARD_WIDTH / 2
        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)
        g.centeredText(font, "Not signed in", centerX, CARD_TOP + 18, Ui.WHITE)
        g.centeredText(
            font,
            "Sign in to queue for ranked matches.",
            centerX, CARD_TOP + 32, Ui.TEXT_DIM,
        )
    }

    private fun drawProfileCard(g: GuiGraphicsExtractor, centerX: Int, profile: PlayerProfile) {
        val left = centerX - CARD_WIDTH / 2
        val right = left + CARD_WIDTH
        val tierColor = Ui.tierColor(profile.tier)

        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)
        // Banner image behind the card (mcsr BannerCardWidget look). Inset so the
        // panel's nine-slice corners still frame it; the helper dims it for text.
        Ui.drawUserBackground(g, left + 3, CARD_TOP + 3, CARD_WIDTH - 6, CARD_HEIGHT - 6, profile.background)
        Ui.accentBar(g, left, CARD_TOP, CARD_HEIGHT, tierColor)

        val padLeft = left + 10
        val padRight = right - 10

        // Avatar on the left, crest on the right, text in the column between —
        // the crest previously sat inline and collided with the line below it.
        Ui.slot(g, padLeft - 2, CARD_TOP + 8, 28)
        PlayerHeads.draw(g, padLeft, CARD_TOP + 10, 24, profile.uuid, profile.name, tierColor)

        Ui.slot(g, padRight - 30, CARD_TOP + 8, 30)
        Ui.rankBadge(g, padRight - 27, CARD_TOP + 11, profile.tier, size = 24)

        val textLeft = padLeft + 32
        var nameX = textLeft
        if (RankedState.showFlags && !RankedState.hideOwnFlag) {
            profile.country?.let {
                Ui.flagIcon(g, nameX, CARD_TOP + 9, it, 9)
                nameX += 12
            }
        }
        // Header column: name / tier / rating on a 12px grid, one colour each.
        // No mid-string colour switches — the crest and accent bar already carry
        // the tier hue, so the text stays greyscale.
        // Stops at the crest slot: a 16-character name in wide glyphs otherwise
        // runs under it, and the flag has already eaten 12px of the run-up.
        g.text(font, Ui.fit(font, profile.name, padRight - 34 - nameX), nameX, CARD_TOP + 8, Ui.WHITE)
        g.text(font, profile.tier, textLeft, CARD_TOP + 20, tierColor)

        val ratingLine = if (RankedState.hideElo) "MMR hidden"
            else profile.rank?.let { "${profile.rating} MMR · Rank #$it" } ?: "${profile.rating} MMR"
        g.text(font, ratingLine, textLeft, CARD_TOP + 32, Ui.TEXT_DIM)

        // One quiet secondary line — placements, else streak, else season.
        val secondary = when {
            profile.placementMatchesRemaining > 0 ->
                "${profile.placementMatchesRemaining} placement matches left"
            RankedState.winStreak >= 2 -> "${RankedState.winStreak} win streak"
            else -> "Season ${profile.season}"
        }
        g.text(font, secondary, textLeft, CARD_TOP + 44, Ui.TEXT_FAINT)

        // Record in neutral text — the colour lives in the win-rate bar below it,
        // not smeared across the numbers.
        val record = "${profile.wins}W · ${profile.losses}L" +
            if (profile.draws > 0) " · ${profile.draws}D" else ""
        g.text(font, record, padLeft, CARD_TOP + 62, Ui.TEXT_DIM)
        if (profile.wins + profile.losses > 0) {
            Ui.textRight(g, font, "${Ui.winRatePercent(profile)}%", padRight, CARD_TOP + 62, Ui.TEXT_FAINT)
        }
        Ui.winRateBar(g, padLeft, CARD_TOP + 74, CARD_WIDTH - 20, profile.wins, profile.losses, profile.draws)
    }

    private fun drawLoadingCard(g: GuiGraphicsExtractor, centerX: Int) {
        val left = centerX - CARD_WIDTH / 2
        val right = left + CARD_WIDTH
        Ui.panel(g, left, CARD_TOP, CARD_WIDTH, CARD_HEIGHT)

        val padLeft = left + 10
        val padRight = right - 10
        // placeholders: avatar and crest slots
        Ui.slot(g, padLeft - 2, CARD_TOP + 8, 28)
        Ui.slot(g, padRight - 30, CARD_TOP + 8, 30)

        val sk = Ui.SKELETON
        val y0 = CARD_TOP + 8
        // header column skeleton, matching the real card's 12px grid
        g.fill(padLeft + 32, y0, padRight - 40, y0 + 6, sk)        // name
        g.fill(padLeft + 32, y0 + 12, padRight - 60, y0 + 18, sk)  // tier
        g.fill(padLeft + 32, y0 + 24, padRight - 50, y0 + 30, sk)  // rating
        g.fill(padLeft + 32, y0 + 36, padRight - 120, y0 + 42, sk) // secondary
        // record + bar placeholders
        g.fill(padLeft, CARD_TOP + 62, padLeft + 80, CARD_TOP + 68, sk)
        g.fill(padLeft, CARD_TOP + 74, padRight, CARD_TOP + 77, Ui.TRACK)

        g.centeredText(font, "Loading…", centerX, CARD_TOP + CARD_HEIGHT + 12, Ui.TEXT_DIM)
    }

    private fun drawStatusLine(g: GuiGraphicsExtractor, centerX: Int) {
        val y = statusY

        val snapshot = RankedState.queueSnapshot
        if (snapshot != null) {
            // searching indicator: dots cycle so the screen never looks frozen
            val dots = ".".repeat(((System.currentTimeMillis() / 500) % 4).toInt())
            if (snapshot.preparingMatch) {
                // Paired already — the wait now is the match server booting,
                // which is a very different thing to still be looking at.
                g.centeredText(font, "Opponent found — starting server$dots", centerX, y, Ui.ACCENT)
                g.centeredText(
                    font,
                    "§7${Ui.duration(snapshot.waitedSeconds)} · this can take up to a minute",
                    centerX, y + 12, Ui.TEXT_DIM,
                )
                return
            }
            g.centeredText(font, "Searching for an opponent$dots", centerX, y, Ui.ACCENT)
            g.centeredText(
                font,
                "§7${Ui.duration(snapshot.waitedSeconds)} elapsed · ${snapshot.playersInQueue} in queue" +
                    etaSuffix(snapshot.etaSeconds),
                centerX, y + 12, Ui.TEXT_DIM,
            )
            return
        }

        (RankedState.queueStatus ?: RankedState.statusMessage)?.let { status ->
            // Often the server's own refusal text (a party's startBlockedReason,
            // a queue error), so its length is not ours to assume.
            g.centeredText(font, Ui.fit(font, status, width - 20), centerX, y, Ui.WHITE)
        }
    }

    /**
     * The " · est. …" tail of the searching line, or nothing at all.
     *
     * The estimate is the backend's — derived from how long the matchmaker's
     * MMR bands still need to widen to cover the nearest queued opponent. This
     * used to be guessed client-side from the headcount, which cheerfully
     * promised "any moment" to a player nobody in the queue could be matched
     * with. When the server cannot name a time (nobody in range) we say
     * nothing rather than invent one.
     */
    private fun etaSuffix(etaSeconds: Long?): String = when {
        etaSeconds == null -> ""
        etaSeconds <= 5 -> " · est. any moment"
        else -> " · est. ~${Ui.duration(etaSeconds)}"
    }

    private fun drawDisabledHints(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        fun isHovering(btn: RankedButton?): Boolean {
            if (btn == null) return false
            val bx = btn.x
            val by = btn.y
            val bw = btn.width
            val bh = btn.height
            return mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bh
        }

        // Hint shown as a cursor tooltip, not stamped under the button — the
        // buttons stack only 4px apart, so text below one landed on the next.
        val hint = when {
            pickerButton?.active == false && isHovering(pickerButton) -> "Format locked while queued"
            queueButton?.active == false && isHovering(queueButton) -> "Please sign in first"
            else -> null
        }
        if (hint != null) drawTooltip(g, mouseX, mouseY, hint)
    }

    /** Small boxed tooltip near the cursor, clamped to the screen. Drawn in the
     *  content pass so it sits above the buttons. */
    private fun drawTooltip(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, text: String) {
        val w = font.width(text)
        val x = (mouseX + 10).coerceAtMost(width - w - 6)
        val y = (mouseY - 14).coerceAtLeast(2)
        Ui.panel(g, x - 3, y - 3, w + 6, 14)
        g.text(font, text, x, y, Ui.WHITE)
    }

    override fun onClose() {
        // leaving the screen does not leave the queue; the socket keeps running
        // and auto-connects when a match is found
        RankedState.onRankedScreen = false
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val CARD_WIDTH = 220
        const val CARD_HEIGHT = 84
        const val CARD_TOP = 46

        /** Vertical rhythm for the button column: 20px button + 4px gap. */
        const val ROW = 24
        /** Space between the card and the status line. */
        const val STATUS_GAP = 6
        /** Height reserved for the (up to two-line) status strip under the card. */
        const val STATUS_STRIP = 26

        /** Top-left corner of the party strip. Clear of the centred card at
         *  every GUI scale, and where a player already looks for a party list. */
        const val PARTY_BOX_X = 6
        const val PARTY_BOX_Y = 6
    }
}
