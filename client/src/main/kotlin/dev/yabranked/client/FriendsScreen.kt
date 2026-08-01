package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.Friend
import dev.yabranked.proto.FriendListResponse
import dev.yabranked.proto.PresenceState
import dev.yabranked.proto.RecentPlayer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * Friends, pending requests, and the people you may still add.
 *
 * The "add" tab is a name box plus a shortcut list. Anyone who has used the
 * ranked mod can be added by name; the list below is simply the people you most
 * recently played with, since that is who you usually mean.
 */
class FriendsScreen(
    private val parent: Screen?,
) : ScaledScreen(Component.literal("Friends")) {

    private enum class Tab(val title: String) { FRIENDS("Friends"), REQUESTS("Requests"), ADD("Add") }

    private var tab = Tab.FRIENDS
    private var friends: Loadable<FriendListResponse> = Loadable.Loading
    private var recent: Loadable<List<RecentPlayer>> = Loadable.Loading
    private var scroll = 0
    private var openedAt = 0L

    /** True while a fetch is in flight, so the Refresh button cannot stack them. */
    private var refreshing = false

    /** Ticks until the next automatic refresh; see [tick]. */
    private var autoRefreshIn = AUTO_REFRESH_TICKS

    /** [RankedState.socialRevision] the current list was loaded at. */
    private var loadedRevision = RankedState.socialRevision

    /**
     * Whether the load in flight was asked for by the player.
     *
     * A refresh owes an answer, but the answer used to be a line of text under
     * the title, and the title's plate is only 24px tall — "updated just now"
     * landed on top of it. A notice says the same thing where notices already
     * go, and leaves when it is done. Only manual refreshes raise one: the
     * automatic poll runs every few seconds and would otherwise be a permanent
     * column in the corner.
     */
    private var announceRefresh = false

    /**
     * Clickable rectangles drawn this frame, innermost first.
     *
     * Rebuilt every frame from what was actually drawn, so a button belonging to
     * a row the server has since removed cannot be clicked a frame later.
     */
    private val hits = mutableListOf<Hit>()

    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val run: () -> Unit)

    /**
     * The friend whose Remove button is armed. Removing is one click away from
     * an Invite button, so the first click asks and the second one does it.
     */
    private var confirmRemove: String? = null

    /** Name entry on the Add tab; null while another tab is selected. */
    private var nameBox: EditBox? = null

    private val listTop get() = if (tab == Tab.ADD) 80 else 58
    private val listBottom get() = height - 34

    override fun layout() {
        // Inviting is the point of this screen and it travels over the
        // party socket, so make sure there is one.
        if (RankedState.isAuthenticated) RankedParty.connect()
        val centerX = width / 2
        Tab.entries.forEachIndexed { index, entry ->
            val w = 66
            val x = centerX - (Tab.entries.size * w) / 2 + index * w
            addRenderableWidget(
                RankedButton(x, 34, w - 2, 18, Component.literal(tabLabel(entry))) {
                    tab = entry
                    scroll = 0
                    rebuildWidgets()
                }
            ).active = tab != entry
        }
        if (tab == Tab.ADD) {
            val left = centerX - 120
            // The glyph sits in the gap left of the field rather than inside it:
            // an EditBox draws its own background over anything underneath.
            nameBox = addRenderableWidget(
                EditBox(font, left + SEARCH_GLYPH, 58, 168 - SEARCH_GLYPH, 16, Component.literal("Player name")).apply {
                    setHint(Component.literal("Search for a player…"))
                    setMaxLength(16)
                    value = nameBox?.value ?: ""
                }
            )
            addRenderableWidget(
                RankedButton(left + 172, 57, 68, 18, Component.literal("Add"), Ui.ICON_FRIENDS) { addByName() }
            )
        } else {
            nameBox = null
        }

        // Refresh sits with the tabs rather than at the bottom: the tab it
        // matters for is Requests, and that is where the eye already is.
        addRenderableWidget(
            RankedButton(
                centerX + (Tab.entries.size * 66) / 2 + 4, 34, 20, 18,
                Component.literal("Refresh"), Ui.ICON_REFRESH, iconOnly = true,
            ) { manualRefresh() }
        ).tip("Check for new friend requests")

        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        if (friends is Loadable.Loading) load()
    }

    /** The Refresh button: same fetch, plus the feedback a button press owes. */
    private fun manualRefresh() {
        if (refreshing) {
            Sfx.tick()
            return
        }
        Sfx.select()
        announceRefresh = true
        load()
    }

    private fun tabLabel(entry: Tab): String = when (entry) {
        Tab.REQUESTS -> {
            val n = friends.valueOrNull?.incoming?.size ?: 0
            if (n > 0) "${entry.title} ($n)" else entry.title
        }
        else -> entry.title
    }

    private fun load() {
        val backend = RankedState.backend ?: run {
            friends = Loadable.Failed("Sign in to see your friends")
            return
        }
        if (refreshing) return
        refreshing = true
        autoRefreshIn = AUTO_REFRESH_TICKS
        // Only the first load shows the "Loading…" placeholder. A refresh over a
        // list that is already on screen keeps it there and swaps it when the
        // answer lands — blanking it every few seconds is what makes a polling
        // list unusable.
        if (friends !is Loadable.Loaded) friends = Loadable.Loading
        if (recent !is Loadable.Loaded) recent = Loadable.Loading
        // Captured before the fetch: news that arrives while it is in flight
        // must still mark the result stale, not be swallowed by it.
        val revision = RankedState.socialRevision
        YabRankedClient.workers.execute {
            val list = backend.fetchFriends()
            val people = backend.fetchRecentPlayers()
            minecraft.execute {
                refreshing = false
                loadedRevision = revision
                // A failed refresh leaves the list that is already on screen
                // alone; replacing it with an error would throw away good rows
                // over one flaky request.
                if (list is BackendClient.Fetch.Ok || friends !is Loadable.Loaded) {
                    friends = list.toLoadable()
                }
                if (people is BackendClient.Fetch.Ok || recent !is Loadable.Loaded) {
                    recent = people.toLoadable()
                }
                // The badge on the ranked screen is only ever an approximation
                // between refreshes; this is the authoritative count.
                RankedState.friendRequests =
                    friends.valueOrNull?.incoming?.size ?: RankedState.friendRequests
                if (announceRefresh) {
                    announceRefresh = false
                    if (list is BackendClient.Fetch.Ok) {
                        val pending = friends.valueOrNull?.incoming?.size ?: 0
                        RankedNotice.info(
                            if (pending > 0) "$pending pending request${if (pending == 1) "" else "s"}"
                            else "No new requests",
                            title = "Friends",
                        )
                    } else {
                        RankedNotice.error("Could not refresh — try again", title = "Friends")
                    }
                }
                rebuildWidgets()
            }
        }
    }

    /**
     * Send a request to whoever currently holds that name. The backend resolves
     * it against every account that has used the mod and answers with its own
     * refusal text, which is shown as-is.
     */
    private fun addByName() {
        val name = nameBox?.value?.trim().orEmpty()
        if (name.isEmpty()) {
            RankedNotice.error("Enter a player name", title = "Friends")
            Sfx.tick()
            return
        }
        val backend = RankedState.backend ?: return
        YabRankedClient.workers.execute {
            val error = backend.sendFriendRequestByName(name)
            minecraft.execute {
                if (error != null) RankedNotice.error(error, title = "Friends")
                else RankedNotice.info("Request sent to $name", title = "Friends")
                if (error == null) {
                    Sfx.select()
                    nameBox?.value = ""
                } else {
                    Sfx.tick()
                }
                load()
            }
        }
    }

    /** Runs [action] off-thread, then reloads so the screen shows the result. */
    private fun act(action: (BackendClient) -> String?) {
        val backend = RankedState.backend ?: return
        YabRankedClient.workers.execute {
            val error = action(backend)
            minecraft.execute {
                if (error != null) RankedNotice.error(error, title = "Friends")
                if (error == null) Sfx.select() else Sfx.tick()
                load()
            }
        }
    }

    /**
     * An invite that arrives while this screen is open gets the same popup it
     * would get on the ranked menu — this is where someone waiting for one is
     * most likely to be looking.
     *
     * The request list is refreshed from here too. Requests reach the client as
     * a party-socket push, but the list itself is an HTTP snapshot the push
     * cannot patch, so the screen used to keep showing whatever it fetched when
     * it opened until the player left and came back. Two triggers: the socket
     * bumping [RankedState.socialRevision] (instant, the normal case) and a slow
     * poll underneath it, which covers a request that landed while the socket
     * was down.
     */
    override fun tick() {
        RankedState.partyInvite?.let { RankedParty.promptForInvite(it, parent = this) }

        if (RankedState.socialRevision != loadedRevision) {
            load()
            return
        }
        if (--autoRefreshIn <= 0) load()
    }

    // --- interaction ---

    /** Rows the current tab has, so the wheel cannot run past the last one. */
    private fun rowCount(): Int = when (tab) {
        Tab.FRIENDS -> friends.valueOrNull?.friends?.size ?: 0
        Tab.REQUESTS -> (friends.valueOrNull?.incoming?.size ?: 0) + (friends.valueOrNull?.outgoing?.size ?: 0)
        Tab.ADD -> recent.valueOrNull?.size ?: 0
    }

    override fun onMouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        // Clamped at both ends: with only a floor the wheel scrolled the list
        // clean off the top of the viewport and left the tab looking empty.
        val maxScroll = (rowCount() * ROW - (listBottom - listTop)).coerceAtLeast(0)
        scroll = (scroll - (vAmount * ROW).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (event.y() >= listTop && event.y() < listBottom) {
            for (hit in hits) {
                if (event.x() >= hit.x && event.x() < hit.x + hit.w &&
                    event.y() >= hit.y && event.y() < hit.y + hit.h
                ) {
                    hit.run()
                    return true
                }
            }
        }
        // A click on empty space disarms a pending remove, so it cannot sit
        // armed while the player does something else.
        if (confirmRemove != null) {
            confirmRemove = null
            return true
        }
        return super.onMouseClicked(event, doubled)
    }

    // --- rendering ---

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        hits.clear()

        val centerX = width / 2
        Ui.header(g, centerX - 110, 6, 220, 24)
        g.centeredText(font, "§lFRIENDS", centerX, 13, Ui.ACCENT)

        val listWidth = (width - 60).coerceIn(200, 420)
        val left = (width - listWidth) / 2

        if (tab == Tab.ADD) {
            Ui.icon(g, Ui.ICON_SEARCH, centerX - 120, 59, 12, Ui.TEXT_SOFT)
        }

        g.enableScissor(0, listTop, width, listBottom)
        when (tab) {
            Tab.FRIENDS -> drawFriends(g, left, listWidth, mouseX, mouseY)
            Tab.REQUESTS -> drawRequests(g, left, listWidth, mouseX, mouseY)
            Tab.ADD -> drawAddable(g, left, listWidth, mouseX, mouseY)
        }
        g.disableScissor()

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        // The notice stack is drawn once, for every screen, by
        // YabRankedClient — not here, or a screen stacked on another would
        // paint a second copy of it.
        Ui.fadeIn(g, width, height, openedAt)
    }

    /** Draws one row's plate and returns its top y. */
    private fun row(g: GuiGraphicsExtractor, left: Int, width: Int, index: Int): Int {
        val y = listTop + index * ROW - scroll
        Ui.row(g, left, y, width, ROW - 2)
        return y
    }

    /** One labelled action button. Returns its left edge so buttons can stack. */
    private fun actionButton(
        g: GuiGraphicsExtractor,
        right: Int,
        y: Int,
        label: String,
        color: Int,
        mouseX: Int,
        mouseY: Int,
        run: () -> Unit,
    ): Int {
        val w = font.width(label) + 10
        val h = 14
        val x = right - w
        val top = y + (ROW - 2 - h) / 2
        val hovered = mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + h &&
            mouseY >= listTop && mouseY < listBottom
        g.fill(x, top, x + w, top + h, if (hovered) Ui.BUTTON_BG_LIT else Ui.BUTTON_BG)
        g.fill(x, top, x + w, top + 1, Ui.BUTTON_BORDER)
        g.fill(x, top + h - 1, x + w, top + h, Ui.BUTTON_BORDER)
        g.centeredText(font, label, x + w / 2, top + 3, if (hovered) Ui.WHITE else color)
        hits += Hit(x, top, w, h, run)
        return x - 3
    }

    /**
     * A right-anchored status word where a button would otherwise be.
     *
     * Returns its left edge for the same reason [actionButton] does: whatever is
     * drawn to the left of it — the name, the status line — has to know where this
     * stops, and a row that mixes buttons and plain labels must measure both the
     * same way.
     */
    private fun label(g: GuiGraphicsExtractor, right: Int, y: Int, text: String, color: Int): Int {
        Ui.textRight(g, font, text, right, y + 8, color)
        return right - font.width(text) - 3
    }

    private fun empty(g: GuiGraphicsExtractor, text: String) {
        g.centeredText(font, text, width / 2, listTop + 20, Ui.TEXT_DIM)
    }

    private fun drawFriends(g: GuiGraphicsExtractor, left: Int, w: Int, mouseX: Int, mouseY: Int) {
        val list = when (val state = friends) {
            is Loadable.Loading -> return empty(g, "Loading…")
            is Loadable.Failed -> return empty(g, "§c${state.message}")
            is Loadable.Loaded -> state.value.friends
        }
        if (list.isEmpty()) return empty(g, "No friends yet — add someone by name.")

        list.forEachIndexed { index, friend ->
            val y = row(g, left, w, index)

            // Right-hand controls first, and the text second. They are drawn in
            // this order because the buttons are opaque fills: text drawn after
            // them lands *on top of* them, which is what the overlap looked like.
            var right = left + w - 6
            // Remove asks once: it sits next to Invite, and an accidental click
            // would otherwise silently drop the friendship.
            right = if (confirmRemove == friend.player.uuid) {
                actionButton(g, right, y, "Sure?", Ui.LOSS, mouseX, mouseY) {
                    confirmRemove = null
                    act { it.removeFriend(friend.player.uuid) }
                }
            } else {
                actionButton(g, right, y, "Remove", Ui.LOSS, mouseX, mouseY) {
                    confirmRemove = friend.player.uuid
                    Sfx.tick()
                }
            }

            val textRight = when {
                friend.inYourParty -> label(g, right, y, "in your party", Ui.ACCENT)
                !friend.invitable -> label(g, right, y, "invites off", Ui.TEXT_FAINT)
                else -> actionButton(g, right, y, "Invite", Ui.TEXT_SOFT, mouseX, mouseY) { invite(friend) }
            }

            drawPerson(g, left, y, textRight, friend.player.uuid, friend.player.name, friend.tier)

            val presence = when (friend.presence) {
                PresenceState.OFFLINE -> "offline"
                PresenceState.MENUS -> "in menus"
                PresenceState.QUEUE -> "in queue"
                PresenceState.PARTY -> "in a party"
                PresenceState.MATCH -> "in a match"
            }
            drawSubLine(
                g, left, y, textRight,
                text = presence,
                color = if (friend.presence == PresenceState.OFFLINE) Ui.TEXT_FAINT else Ui.WIN,
                trailing = friend.rating?.let { "$it MMR" },
            )
        }
    }

    private fun drawRequests(g: GuiGraphicsExtractor, left: Int, w: Int, mouseX: Int, mouseY: Int) {
        val list = when (val state = friends) {
            is Loadable.Loading -> return empty(g, "Loading…")
            is Loadable.Failed -> return empty(g, "§c${state.message}")
            is Loadable.Loaded -> state.value
        }
        val rows = list.incoming.map { it to true } + list.outgoing.map { it to false }
        if (rows.isEmpty()) return empty(g, "No pending requests.")

        rows.forEachIndexed { index, (request, incoming) ->
            val other = if (incoming) request.from else request.to
            val y = row(g, left, w, index)

            var right = left + w - 6
            val textRight = if (incoming) {
                // Both answers are one click, and neither is the row itself —
                // accepting a friend request by misclicking a list is exactly
                // what an explicit pair of buttons prevents.
                right = actionButton(g, right, y, "Reject", Ui.LOSS, mouseX, mouseY) {
                    act { it.dismissFriendRequest(request.id) }
                }
                actionButton(g, right, y, "Accept", Ui.WIN, mouseX, mouseY) {
                    act { it.acceptFriendRequest(request.id) }
                }
            } else {
                actionButton(g, right, y, "Cancel", Ui.TEXT_SOFT, mouseX, mouseY) {
                    act { it.dismissFriendRequest(request.id) }
                }
            }

            drawPerson(g, left, y, textRight, other.uuid, other.name, null)
            drawSubLine(
                g, left, y, textRight,
                text = if (incoming) "wants to be friends" else "request sent",
                color = Ui.TEXT_DIM,
            )
        }
    }

    private fun drawAddable(g: GuiGraphicsExtractor, left: Int, w: Int, mouseX: Int, mouseY: Int) {
        val list = when (val state = recent) {
            is Loadable.Loading -> return empty(g, "Loading…")
            is Loadable.Failed -> return empty(g, "§c${state.message}")
            is Loadable.Loaded -> state.value
        }
        if (list.isEmpty()) {
            return empty(g, "Nobody yet — add a friend by name above.")
        }

        list.forEachIndexed { index, person ->
            val y = row(g, left, w, index)
            val right = left + w - 6
            val textRight = when {
                person.alreadyFriends -> label(g, right, y, "friends", Ui.WIN)
                person.requestPending -> label(g, right, y, "pending", Ui.ACCENT)
                // The widest of these, and the one the fixed-width name used to
                // run straight through.
                !person.requestable -> label(g, right, y, "not accepting", Ui.TEXT_FAINT)
                else -> actionButton(g, right, y, "Add", Ui.TEXT_SOFT, mouseX, mouseY) {
                    act { it.sendFriendRequest(person.player.uuid) }
                }
            }

            drawPerson(g, left, y, textRight, person.player.uuid, person.player.name, null)
            drawSubLine(
                g, left, y, textRight,
                text = "${person.matchesTogether} match${if (person.matchesTogether == 1) "" else "es"} together",
                color = Ui.TEXT_DIM,
            )
        }
    }

    /**
     * The head, and the name fitted to whatever the row's controls left over.
     *
     * [textRight] is the real boundary, passed in by the caller *after* it has
     * drawn its right-hand controls. It used to be a fixed `w - 120`, which is
     * wrong at every width: the controls are right-anchored and their widths come
     * from their labels, so the space they take does not scale with the row and a
     * long name ran straight under "in your party" or "not accepting".
     */
    private fun drawPerson(
        g: GuiGraphicsExtractor,
        left: Int,
        y: Int,
        textRight: Int,
        uuid: String,
        name: String,
        tier: String?,
    ) {
        val accent = tier?.let(Ui::tierColor) ?: Ui.TEXT_SOFT
        PlayerHeads.draw(g, left + 6, y + 5, 18, uuid, name, accent)
        // Nothing at all when there is no room, rather than everything: `Ui.fit`
        // returns its input unchanged for a non-positive width, which is a sane
        // default in general and here would put the name back over the controls
        // it was measured against.
        val available = textRight - (left + TEXT_LEFT)
        if (available <= 0) return
        g.text(font, Ui.fit(font, name, available), left + TEXT_LEFT, y + 4, Ui.WHITE)
    }

    /**
     * The row's second line, fitted the same way.
     *
     * [trailing] is dropped rather than squeezed when it does not fit: an MMR that
     * has to overlap the status to be shown is worse than one that is not shown,
     * and the profile screen has the number anyway.
     */
    private fun drawSubLine(
        g: GuiGraphicsExtractor,
        left: Int,
        y: Int,
        textRight: Int,
        text: String,
        color: Int,
        trailing: String? = null,
        trailingColor: Int = Ui.TEXT_DIM,
    ) {
        val x = left + TEXT_LEFT
        val available = textRight - x
        if (available <= 0) return
        g.text(font, Ui.fit(font, text, available), x, y + 15, color)
        if (trailing == null) return
        val trailingX = x + font.width(text) + 8
        if (trailingX + font.width(trailing) <= textRight) {
            g.text(font, trailing, trailingX, y + 15, trailingColor)
        }
    }

    private fun invite(friend: Friend) {
        if (friend.inYourParty || !friend.invitable) {
            Sfx.tick()
            return
        }
        // Invites travel over the party socket. With it down the message is
        // dropped, so say that instead of claiming an invite was sent — the
        // silent version of this is indistinguishable from being ignored.
        if (!RankedParty.isConnected) {
            RankedParty.connect()
            RankedNotice.error("Not connected to the party service — try again in a moment")
            Sfx.tick()
            return
        }
        // No explicit create: the server makes a party on the first invite if
        // the inviter has none. Sending Create first was redundant, and it is
        // what put both players in their own one-person party.
        RankedParty.invite(friend.player.uuid)
        RankedNotice.info("Invited ${friend.player.name}")
        LoggerFactory.getLogger("yabranked-client")
            .info("invite pressed for {} ({})", friend.player.name, friend.player.uuid)
        Sfx.select()
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val ROW = 30

        /** Where a row's text starts: past the 18px head at `left + 6`. */
        const val TEXT_LEFT = 30

        /** Gutter reserved left of the name field for the search glyph. */
        const val SEARCH_GLYPH = 16

        /**
         * Ticks between background refreshes: 15s. Slow on purpose — the party
         * socket is what makes a new request appear promptly, and this is only
         * the floor under it for when that socket is down.
         */
        const val AUTO_REFRESH_TICKS = 300
    }
}
