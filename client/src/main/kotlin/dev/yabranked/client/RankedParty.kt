package dev.yabranked.client

import dev.yabranked.proto.PartyClientMessage
import dev.yabranked.proto.PartyOptions
import dev.yabranked.proto.PartyServerMessage
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Owns the party socket, independently of any screen.
 *
 * Same shape and the same reason as [RankedQueue]: a party outlives the screen
 * that created it, invites arrive while the player is somewhere else in the
 * menus, and the leader's changes have to reach every member's client whatever
 * they happen to be looking at.
 *
 * The client never edits [RankedState.party] itself. Every mutation is a
 * request; the authoritative state arrives back as a full
 * [PartyServerMessage.State], so a refused action simply leaves the view as it
 * was rather than needing a rollback.
 */
object RankedParty {

    private val log = LoggerFactory.getLogger("yabranked-party")

    private const val MAX_RECONNECTS = 5
    private const val RECONNECT_BASE_MS = 1000L
    private const val RECONNECT_MAX_MS = 8000L

    /** Keeps presence alive while the player sits in a menu; see the 90s TTL. */
    private const val PING_INTERVAL_SECONDS = 30L

    /**
     * How long to stay quiet after exhausting the retries.
     *
     * [connect] is called from the client tick so the socket is always up while
     * signed in; without a cooldown, giving up would immediately be undone by
     * the next tick and the backoff would become a 20-per-second hammer on a
     * backend that is already unwell.
     */
    private const val GIVE_UP_COOLDOWN_MS = 30_000L

    private var socket: BackendClient.PartySocket? = null
    private var wanted = false
    private var reconnects = 0
    private var pending: ScheduledFuture<*>? = null
    private var pings: ScheduledFuture<*>? = null
    private var generation = 0

    /** Wall-clock before which [connect] refuses to try again. */
    private var quietUntil = 0L

    val isConnected: Boolean get() = socket != null

    /**
     * Open the socket if it is not already open or being retried.
     *
     * Safe to call every tick: it is a no-op while one is live, while a
     * reconnect is scheduled, and during the cooldown after giving up.
     */
    fun connect() {
        if (RankedState.backend == null || wanted) return
        if (System.currentTimeMillis() < quietUntil) return
        wanted = true
        reconnects = 0
        open(++generation)
    }

    /** Close for good — signing out, or leaving the ranked menus entirely. */
    fun disconnect() {
        generation++
        wanted = false
        quietUntil = 0
        pending?.cancel(false)
        pending = null
        pings?.cancel(false)
        pings = null
        socket?.close()
        socket = null
        RankedState.party = null
        RankedState.partyInvite = null
    }

    private fun send(message: PartyClientMessage) {
        val open = socket
        log.info("party -> {}{}", message::class.simpleName, if (open == null) " (DROPPED: no socket)" else "")
        if (open == null) {
            // The socket is the only way to act on a party, so a queued command
            // would be a command applied against a roster the player can no
            // longer see. Reconnect and let them press it again.
            connect()
            RankedNotice.error("Reconnecting to the party service…")
            return
        }
        open.send(message)
    }

    fun create() = send(PartyClientMessage.Create)
    fun invite(uuid: String) = send(PartyClientMessage.Invite(uuid))
    fun accept(partyId: String) = send(PartyClientMessage.AcceptInvite(partyId))
    fun decline(partyId: String) = send(PartyClientMessage.DeclineInvite(partyId))
    fun leave() = send(PartyClientMessage.Leave)
    fun kick(uuid: String) = send(PartyClientMessage.Kick(uuid))
    fun promote(uuid: String) = send(PartyClientMessage.Promote(uuid))
    fun setOptions(options: PartyOptions) = send(PartyClientMessage.SetOptions(options))
    fun setTeam(uuid: String, team: Int?) = send(PartyClientMessage.SetTeam(uuid, team))
    fun setReady(ready: Boolean) = send(PartyClientMessage.SetReady(ready))

    /** Leader-only, party-only formats: start the party's own match now. */
    fun startMatch() = send(PartyClientMessage.StartMatch)

    private fun open(id: Int) {
        val minecraft = Minecraft.getInstance()
        val backend = RankedState.backend
        if (backend == null) {
            // `wanted` is already true at this point, so returning without
            // clearing it would wedge the socket shut for the rest of the
            // session: every later connect() would see wanted and no-op.
            wanted = false
            return
        }
        log.info("opening party socket")
        YabRankedClient.workers.execute {
            val opened = backend.connectParty(
                onMessage = { message -> minecraft.execute { if (id == generation) onMessage(message) } },
                onClosed = { reason -> minecraft.execute { if (id == generation) onClosed(reason) } },
            )
            minecraft.execute {
                if (id != generation) {
                    opened?.close()
                    return@execute
                }
                socket = opened
                if (opened != null) {
                    log.info("party socket open")
                    reconnects = 0
                    quietUntil = 0
                    startPings(id)
                }
                // A null socket has already gone through onClosed — every
                // failure path in connectParty reports — so the retry is
                // already scheduled and must not be scheduled twice here.
            }
        }
    }

    private fun startPings(id: Int) {
        pings?.cancel(false)
        val minecraft = Minecraft.getInstance()
        pings = YabRankedClient.workers.scheduleAtFixedRate(
            { minecraft.execute { if (id == generation) socket?.send(PartyClientMessage.Ping) } },
            PING_INTERVAL_SECONDS,
            PING_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun onMessage(message: PartyServerMessage) {
        log.info("party <- {}", message)
        reconnects = 0
        when (message) {
            is PartyServerMessage.State -> {
                val previous = RankedState.party
                RankedState.party = message.party
                // The invite that got them here is answered; anything still
                // showing would be an invite to the party they are now in.
                if (message.party != null) RankedState.partyInvite = null
                announceRoster(previous, message.party)
                followLeaderIntoQueue(previous, message.party)
            }

            is PartyServerMessage.Invited -> {
                RankedState.partyInvite = message.invite
                RankedToast.show("Party invite", "${message.invite.from.name} invited you")
                RankedNotice.info("${message.invite.from.name} invited you to their party")
                promptForInvite(message.invite)
            }

            is PartyServerMessage.Disbanded -> {
                RankedState.party = null
                RankedNotice.info(message.reason)
                RankedToast.show("Party", message.reason)
            }

            is PartyServerMessage.FriendRequested -> {
                RankedState.friendRequests += 1
                // Tells an open friends screen that its list is stale; it holds
                // an HTTP snapshot that this push cannot patch directly.
                RankedState.socialRevision += 1
                RankedToast.show("Friend request", "${message.request.from.name} wants to be friends")
                RankedNotice.info(
                    "${message.request.from.name} wants to be friends",
                    title = "Friend request",
                )
            }

            is PartyServerMessage.Error -> {
                RankedNotice.error(message.message)
            }

            is PartyServerMessage.MatchStarting -> {
                // Identical handling to a queue match — same payload, same
                // screen, same auto-connect. The only difference is which socket
                // it arrived on.
                RankedState.activeMatch = message.match
                RankedNotice.clear()
                Minecraft.getInstance()
                    .setScreenAndShow(MatchFoundScreen(null, message.match))
            }

            is PartyServerMessage.StartFailed -> {
                RankedNotice.error(message.reason, title = "Could not start")
                RankedToast.showError("Could not start", message.reason)
            }
        }
    }

    /**
     * Put the invite on screen, if the player is somewhere it belongs.
     *
     * An invite needs an answer, so it gets a screen rather than only a toast.
     * But it must never take the game away from someone who is playing: during a
     * match, or with any non-ranked screen open (inventory, chat, another mod's
     * GUI), the toast and the buttons on the ranked screen are the whole
     * notification.
     */
    fun promptForInvite(invite: dev.yabranked.proto.PartyInviteView, parent: net.minecraft.client.gui.screens.Screen? = null) {
        if (RankedState.activeMatch != null) return
        val minecraft = Minecraft.getInstance()
        // There is no public accessor for the current screen in this version, so
        // "is the player somewhere interruptible" is answered from the mod's own
        // state: on the ranked menu, or at the main menu with no world loaded.
        // Anywhere else (in a world, in an inventory, in another mod's GUI) the
        // toast and the ranked screen's Join/Decline buttons are the whole
        // notification — never the screen.
        val interruptible = parent != null || RankedState.onRankedScreen || minecraft.level == null
        if (!interruptible) return
        minecraft.setScreenAndShow(PartyInviteScreen(parent, invite))
    }

    /**
     * Say who arrived and who left, from the difference between two rosters.
     *
     * The server only ever pushes whole [PartyView]s, so this is the only place
     * that knows a roster changed at all — without it, players appearing and
     * disappearing from the list is the entire notification, and on any screen
     * other than the party one there is no notification whatsoever.
     */
    private fun announceRoster(
        previous: dev.yabranked.proto.PartyView?,
        current: dev.yabranked.proto.PartyView?,
    ) {
        val self = RankedState.profile?.uuid
        // A different party id is a different roster: everyone in it is "new",
        // which is noise. The one line worth saying is that you joined it.
        if (previous == null || current == null || previous.id != current.id) {
            if (current != null && current.members.size > 1) {
                val leader = current.members.firstOrNull { it.leader }?.player?.name
                val text = if (leader != null && leader != RankedState.profile?.name) {
                    "You joined ${leader}'s party"
                } else {
                    "You joined the party"
                }
                RankedNotice.info(text)
                RankedToast.show("Party", text)
            }
            return
        }

        val before = previous.members.associate { it.player.uuid to it.player.name }
        val after = current.members.associate { it.player.uuid to it.player.name }

        for ((uuid, name) in after) {
            if (uuid in before || uuid == self) continue
            RankedNotice.info("$name joined the party")
            RankedToast.show("Party", "$name joined the party")
        }
        for ((uuid, name) in before) {
            if (uuid in after || uuid == self) continue
            RankedNotice.info("$name left the party")
            RankedToast.show("Party", "$name left the party")
        }
    }

    /**
     * Open (or close) this client's own queue socket to follow the leader.
     *
     * Only the leader's socket holds the party's queue ticket, but every member
     * needs a socket of their own: it is the only channel the backend has to
     * tell them the match is ready and where to connect. So when the leader
     * starts a search, each member's client quietly joins as an observer.
     */
    private fun followLeaderIntoQueue(previous: dev.yabranked.proto.PartyView?, current: dev.yabranked.proto.PartyView?) {
        val wasQueued = previous?.queued == true
        val nowQueued = current?.queued == true
        if (wasQueued == nowQueued) return
        // The leader's own socket is opened by the button they pressed.
        if (RankedState.isPartyLeader) return

        if (nowQueued && current != null) {
            RankedState.selectedFormat = current.options.format
            RankedQueue.join(current.options.format, asParty = true)
        } else if (wasQueued) {
            RankedQueue.leave()
        }
    }

    private fun onClosed(reason: String?) {
        log.warn("party socket closed: {}", reason ?: "no reason given")
        socket = null
        pings?.cancel(false)
        pings = null
        if (!wanted) return

        if (reconnects >= MAX_RECONNECTS) {
            wanted = false
            quietUntil = System.currentTimeMillis() + GIVE_UP_COOLDOWN_MS
            RankedState.party = null
            RankedNotice.error("Lost the party connection")
            // Loud, because everything party-shaped is silently dead without
            // this socket: no invites in, no invites out, and every button on
            // the party screen a no-op. A log line nobody reads is how this
            // stayed invisible.
            RankedToast.showError(
                "Party offline",
                reason ?: "could not reach the party service",
            )
            return
        }

        val delay = (RECONNECT_BASE_MS shl reconnects).coerceAtMost(RECONNECT_MAX_MS)
        reconnects++
        val id = ++generation
        val minecraft = Minecraft.getInstance()
        pending = YabRankedClient.workers.schedule(
            { minecraft.execute { if (id == generation && wanted) open(id) } },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }
}
