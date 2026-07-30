package dev.yabranked.backend.social

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.PartyInviteView
import dev.yabranked.proto.PartyMember
import dev.yabranked.proto.PartyMode
import dev.yabranked.proto.PartyOptions
import dev.yabranked.proto.PartyServerMessage
import dev.yabranked.proto.PartyView
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.PresenceState
import dev.yabranked.proto.TeamAssignment
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** What a party needs to know about one player to render and to balance. */
data class PartyPlayerSnapshot(
    val ref: PlayerRef,
    val rating: Int,
    val tier: String,
    /** Their rating is redacted for other members. */
    val hideRating: Boolean = false,
    /** They refuse party invites outright. */
    val allowInvites: Boolean = true,
    /** They only accept invites from accepted friends. */
    val friendsOnly: Boolean = false,
    val banned: Boolean = false,
)

/** Looks a player up for the party layer without coupling it to the stores. */
fun interface PartyPlayerLookup {
    fun snapshot(uuid: UUID, format: MatchFormat): PartyPlayerSnapshot?
}

/**
 * Parties: who is in one, who leads it, what the leader chose, and who has
 * been invited.
 *
 * **Authority.** The client is never trusted with party state. Every mutation
 * is a request that this service either performs or refuses, and every member
 * is then pushed the whole [PartyView] — never a delta — so a dropped or
 * reordered frame cannot leave two clients disagreeing about who the leader is.
 *
 * **Lifecycle.** A party exists only while it has members. The leader leaving
 * disbands it outright (the requested behaviour): everyone is pushed
 * [PartyServerMessage.Disbanded] and dropped. A non-leader leaving, being
 * kicked, or dropping their socket only removes them; if that empties the
 * party it is disbanded too, and if it drops the party below the selected
 * format's size while queued, the queue entry is cancelled.
 *
 * **Concurrency.** One lock over the whole registry. Parties are small, the
 * operations are microseconds, and the alternative — a lock per party — makes
 * "move a player from party X to party Y" a two-lock ordering problem for no
 * measurable gain. Listener callbacks always run *outside* the lock, since
 * they write to sockets.
 */
class PartyService(
    private val lookup: PartyPlayerLookup,
    private val presence: Presence,
    /** Whether two players are accepted friends; gates friends-only invites. */
    private val areFriends: (UUID, UUID) -> Boolean = { _, _ -> false },
    private val clock: Clock = Clock.systemUTC(),
    val maxSize: Int = 8,
    private val inviteTtl: Duration = Duration.ofMinutes(2),
    /** Most invites one party may have outstanding; stops invite spam fan-out. */
    private val maxPendingInvites: Int = 16,
) {
    private val log = LoggerFactory.getLogger("party")

    private class Party(
        val id: UUID,
        var leader: UUID,
        val members: LinkedHashSet<UUID>,
        var options: PartyOptions,
        val ready: MutableSet<UUID> = mutableSetOf(),
        val manualTeams: MutableMap<UUID, Int> = mutableMapOf(),
        /** invitee -> expiry */
        val invites: MutableMap<UUID, Instant> = mutableMapOf(),
        var queued: Boolean = false,
        val createdAt: Instant,
    )

    private val lock = ReentrantLock()
    private val parties = HashMap<UUID, Party>()
    private val memberOf = HashMap<UUID, UUID>()

    /** One live socket per player. Replacing it disconnects the previous one. */
    private val listeners = ConcurrentHashMap<UUID, (PartyServerMessage) -> Unit>()

    /** Fired when a party's queue entry must be dropped (roster changed under it). */
    private val queueCancelListeners = mutableListOf<(UUID, List<UUID>, String) -> Unit>()

    sealed interface Result {
        data class Ok(val party: PartyView?) : Result
        data class Rejected(val reason: String) : Result
    }

    fun onQueueCancelled(listener: (partyId: UUID, members: List<UUID>, reason: String) -> Unit) {
        synchronized(queueCancelListeners) { queueCancelListeners += listener }
    }

    // --- socket registration ---

    /**
     * Attach [sink] as [player]'s event channel. A second connection for the
     * same account replaces the first: two sockets both acting as one player is
     * how a client ends up sending itself contradictory party states.
     */
    fun connect(player: UUID, sink: (PartyServerMessage) -> Unit) {
        val previous = listeners.put(player, sink)
        if (previous != null) {
            previous(PartyServerMessage.Error("signed in from another session"))
        }
        presence.set(player, if (partyIdOf(player) != null) PresenceState.PARTY else PresenceState.MENUS)
        sink(PartyServerMessage.State(viewFor(player)))
    }

    /**
     * Detach [player]'s socket. Only removes the given [sink] — a stale socket
     * closing after it was replaced must not knock the live one out.
     */
    fun disconnect(player: UUID, sink: (PartyServerMessage) -> Unit) {
        if (!listeners.remove(player, sink)) return
        presence.clear(player)
        leave(player, reason = "disconnected")
    }

    // --- queries ---

    fun partyIdOf(player: UUID): UUID? = lock.withLock { memberOf[player] }

    fun membersOf(partyId: UUID): List<UUID> = lock.withLock {
        parties[partyId]?.members?.toList() ?: emptyList()
    }

    fun leaderOf(partyId: UUID): UUID? = lock.withLock { parties[partyId]?.leader }

    fun isLeader(player: UUID): Boolean = lock.withLock {
        val party = memberOf[player]?.let(parties::get) ?: return@withLock false
        party.leader == player
    }

    fun viewFor(player: UUID): PartyView? = lock.withLock {
        memberOf[player]?.let(parties::get)?.let(::render)
    }

    fun viewOf(partyId: UUID): PartyView? = lock.withLock { parties[partyId]?.let(::render) }

    // --- mutations ---

    fun create(player: UUID): Result {
        val (result, pushes) = lock.withLock {
            memberOf[player]?.let(parties::get)?.let {
                // idempotent: pressing "create" while already in one is not an error
                return@withLock Result.Ok(render(it)) to emptyList<UUID>()
            }
            val party = Party(
                id = UUID.randomUUID(),
                leader = player,
                members = linkedSetOf(player),
                options = PartyOptions(),
                createdAt = clock.instant(),
            )
            parties[party.id] = party
            memberOf[player] = party.id
            Result.Ok(render(party)) to listOf(player)
        }
        presence.set(player, PresenceState.PARTY)
        broadcast(pushes)
        return result
    }

    /**
     * Invite [target] to [inviter]'s party, creating one if the inviter has
     * none. The target's own toggles decide whether the invite is delivered at
     * all — a refused invite is refused here, not silently swallowed by their
     * client.
     */
    fun invite(inviter: UUID, target: UUID): Result {
        if (inviter == target) return Result.Rejected("you cannot invite yourself")

        // create-on-invite so "+" works from an empty party box
        if (partyIdOf(inviter) == null) {
            val created = create(inviter)
            if (created is Result.Rejected) return created
        }

        val snapshot = lookup.snapshot(target, MatchFormat.LOCKOUT_1V1)
            ?: return Result.Rejected("unknown player")
        if (snapshot.banned) return Result.Rejected("unknown player")
        if (!snapshot.allowInvites) return Result.Rejected("this player is not accepting party invites")
        if (snapshot.friendsOnly && !areFriends(inviter, target)) {
            return Result.Rejected("this player only accepts invites from friends")
        }

        val (result, invitePush) = lock.withLock {
            val party = memberOf[inviter]?.let(parties::get)
                ?: return@withLock Result.Rejected("you are not in a party") to null
            if (party.leader != inviter && party.options.partyMode != PartyMode.FREE_FOR_ALL) {
                // members may invite in a casual FFA party, but a leader-run
                // competitive party is the leader's roster to pick
                return@withLock Result.Rejected("only the party leader can invite") to null
            }
            // A party of one is not really a party — it is a player sitting in
            // their own empty lobby, which is the state everyone lands in the
            // moment they press "create". Treating that as "already in a party"
            // made two players who had each opened the party screen permanently
            // unable to invite each other.
            val theirs = memberOf[target]?.let(parties::get)
            if (theirs != null) {
                when {
                    theirs.id == party.id ->
                        return@withLock Result.Rejected("they are already in your party") to null
                    theirs.members.size > 1 ->
                        return@withLock Result.Rejected("they are already in a party") to null
                    theirs.queued ->
                        return@withLock Result.Rejected("they are searching for a match") to null
                }
            }
            if (party.members.size >= maxSize) return@withLock Result.Rejected("party is full") to null
            if (party.queued) return@withLock Result.Rejected("cannot invite while queued") to null
            pruneInvites(party)
            if (party.invites.size >= maxPendingInvites) {
                return@withLock Result.Rejected("too many invites pending") to null
            }
            val expiry = clock.instant().plus(inviteTtl)
            party.invites[target] = expiry
            val invite = PartyInviteView(
                partyId = party.id.toString(),
                from = refOf(inviter),
                members = party.members.map(::refOf),
                expiresAt = expiry.toEpochMilli(),
            )
            Result.Ok(render(party)) to invite
        }
        if (invitePush != null) {
            listeners[target]?.invoke(PartyServerMessage.Invited(invitePush))
            broadcast(membersOf(UUID.fromString(invitePush.partyId)))
        }
        return result
    }

    fun acceptInvite(player: UUID, partyId: UUID): Result {
        val (result, pushes) = lock.withLock {
            val party = parties[partyId] ?: return@withLock Result.Rejected("party no longer exists") to emptyList<UUID>()
            pruneInvites(party)
            val expiry = party.invites[player] ?: return@withLock Result.Rejected("no invite for you") to emptyList()
            if (expiry.isBefore(clock.instant())) {
                party.invites.remove(player)
                return@withLock Result.Rejected("invite expired") to emptyList()
            }
            // Same rule as [invite], from the other side: an empty lobby of
            // their own is dissolved by accepting, but a real party has to be
            // left deliberately — leaving it silently would strand the people
            // still in it.
            val current = memberOf[player]?.let(parties::get)
            if (current != null) {
                if (current.id == party.id) return@withLock Result.Ok(render(party)) to emptyList()
                if (current.members.size > 1) {
                    return@withLock Result.Rejected("leave your current party first") to emptyList()
                }
                if (current.queued) {
                    return@withLock Result.Rejected("you are already searching for a match") to emptyList()
                }
                disbandLocked(current)
            }
            if (party.members.size >= maxSize) return@withLock Result.Rejected("party is full") to emptyList()
            if (party.queued) return@withLock Result.Rejected("that party is already in queue") to emptyList()

            party.invites.remove(player)
            party.members += player
            memberOf[player] = party.id
            // a new member invalidates the leader's team layout and everyone's
            // ready flag: nobody agreed to play with this roster yet
            party.ready.clear()
            party.manualTeams.remove(player)
            Result.Ok(render(party)) to party.members.toList()
        }
        if (result is Result.Ok) presence.set(player, PresenceState.PARTY)
        broadcast(pushes)
        return result
    }

    fun declineInvite(player: UUID, partyId: UUID): Result {
        val members = lock.withLock {
            val party = parties[partyId] ?: return Result.Ok(null)
            party.invites.remove(player)
            party.members.toList()
        }
        broadcast(members)
        return Result.Ok(viewFor(player))
    }

    /**
     * Remove [player] from their party. The leader leaving disbands it, which
     * is the documented behaviour: a party is the leader's lobby, and silently
     * handing it to whoever happens to be second in the set is worse than
     * ending it cleanly.
     */
    fun leave(player: UUID, reason: String = "left the party"): Result {
        data class Fanout(
            val disbandedTo: List<UUID> = emptyList(),
            val stateTo: List<UUID> = emptyList(),
            val cancel: Triple<UUID, List<UUID>, String>? = null,
        )

        val (result, fanout) = lock.withLock {
            val partyId = memberOf[player] ?: return@withLock Result.Ok(null) to Fanout()
            val party = parties[partyId] ?: run {
                memberOf.remove(player)
                return@withLock Result.Ok(null) to Fanout()
            }

            if (party.leader == player) {
                // capture before disbandLocked clears the roster
                val everyone = party.members.toList()
                val others = everyone.filter { it != player }
                val wasQueued = party.queued
                disbandLocked(party)
                return@withLock Result.Ok(null) to Fanout(
                    disbandedTo = others,
                    cancel = if (wasQueued) Triple(party.id, everyone, reason) else null,
                )
            }

            party.members -= player
            party.ready -= player
            party.manualTeams.remove(player)
            memberOf.remove(player)

            if (party.members.isEmpty()) {
                disbandLocked(party)
                return@withLock Result.Ok(null) to Fanout()
            }

            // A roster that no longer fills the selected format cannot stay in
            // the queue: the match would be built one player short.
            val cancel = if (party.queued && !canStartLocked(party).isNullOrEmpty()) {
                party.queued = false
                party.ready.clear()
                Triple(party.id, party.members.toList(), "a party member left")
            } else {
                null
            }
            Result.Ok(null) to Fanout(stateTo = party.members.toList(), cancel = cancel)
        }

        presence.set(player, if (listeners.containsKey(player)) PresenceState.MENUS else PresenceState.OFFLINE)
        for (member in fanout.disbandedTo) {
            listeners[member]?.invoke(PartyServerMessage.Disbanded(reason))
            listeners[member]?.invoke(PartyServerMessage.State(null))
            presence.set(member, PresenceState.MENUS)
        }
        listeners[player]?.invoke(PartyServerMessage.State(null))
        broadcast(fanout.stateTo)
        fanout.cancel?.let { (id, members, why) -> fireQueueCancel(id, members, why) }
        return result
    }

    fun kick(leader: UUID, target: UUID): Result {
        val allowed = lock.withLock {
            val party = memberOf[leader]?.let(parties::get)
                ?: return Result.Rejected("you are not in a party")
            when {
                party.leader != leader -> "only the party leader can kick"
                target == leader -> "use leave to remove yourself"
                target !in party.members -> "they are not in your party"
                else -> null
            }
        }
        allowed?.let { return Result.Rejected(it) }
        listeners[target]?.invoke(PartyServerMessage.Disbanded("you were removed from the party"))
        leave(target, reason = "removed from the party")
        return Result.Ok(viewFor(leader))
    }

    fun promote(leader: UUID, target: UUID): Result {
        val (result, members) = lock.withLock {
            val party = memberOf[leader]?.let(parties::get)
                ?: return@withLock Result.Rejected("you are not in a party") to emptyList<UUID>()
            if (party.leader != leader) return@withLock Result.Rejected("only the party leader can promote") to emptyList()
            if (target !in party.members) return@withLock Result.Rejected("they are not in your party") to emptyList()
            if (party.queued) return@withLock Result.Rejected("cannot change leader while queued") to emptyList()
            party.leader = target
            Result.Ok(render(party)) to party.members.toList()
        }
        broadcast(members)
        return result
    }

    /**
     * Leader-only. Options that no longer fit the roster are corrected rather
     * than refused (a leader shrinking the party should not be stuck with an
     * unstartable selection), and [PartyOptions.ranked] is forced off for any
     * shape that cannot be rated fairly.
     */
    fun setOptions(leader: UUID, options: PartyOptions): Result {
        val (result, members) = lock.withLock {
            val party = memberOf[leader]?.let(parties::get)
                ?: return@withLock Result.Rejected("you are not in a party") to emptyList<UUID>()
            if (party.leader != leader) return@withLock Result.Rejected("only the party leader can change settings") to emptyList()
            if (party.queued) return@withLock Result.Rejected("cannot change settings while queued") to emptyList()
            if (!options.format.playable) return@withLock Result.Rejected("unknown mode") to emptyList()

            party.options = sanitize(options, party.members.size)
            // changing the shape invalidates everyone's agreement to play it
            party.ready.clear()
            if (party.options.teamAssignment != TeamAssignment.MANUAL) party.manualTeams.clear()
            Result.Ok(render(party)) to party.members.toList()
        }
        broadcast(members)
        return result
    }

    fun setTeam(leader: UUID, target: UUID, team: Int?): Result {
        val (result, members) = lock.withLock {
            val party = memberOf[leader]?.let(parties::get)
                ?: return@withLock Result.Rejected("you are not in a party") to emptyList<UUID>()
            if (party.leader != leader) return@withLock Result.Rejected("only the party leader assigns teams") to emptyList()
            if (party.options.teamAssignment != TeamAssignment.MANUAL) {
                return@withLock Result.Rejected("teams are assigned automatically") to emptyList()
            }
            if (target !in party.members) return@withLock Result.Rejected("they are not in your party") to emptyList()
            if (team == null) party.manualTeams.remove(target)
            else if (team < 0 || team >= sideCountLocked(party)) {
                return@withLock Result.Rejected("no such team") to emptyList()
            } else {
                party.manualTeams[target] = team
            }
            party.ready.clear()
            Result.Ok(render(party)) to party.members.toList()
        }
        broadcast(members)
        return result
    }

    fun setReady(player: UUID, ready: Boolean): Result {
        val (result, members) = lock.withLock {
            val party = memberOf[player]?.let(parties::get)
                ?: return@withLock Result.Rejected("you are not in a party") to emptyList<UUID>()
            if (ready) party.ready += player else party.ready -= player
            Result.Ok(render(party)) to party.members.toList()
        }
        broadcast(members)
        return result
    }

    /**
     * Mark the party as queued (or not). Called by the queue layer, not by
     * clients — the flag only exists so the roster and settings freeze while a
     * match is being found for it.
     */
    fun setQueued(partyId: UUID, queued: Boolean): Boolean {
        val members = lock.withLock {
            val party = parties[partyId] ?: return false
            if (queued && (party.queued || !canStartLocked(party).isNullOrEmpty())) return false
            party.queued = queued
            party.members.toList()
        }
        for (member in members) {
            presence.set(member, if (queued) PresenceState.QUEUE else PresenceState.PARTY)
        }
        broadcast(members)
        return true
    }

    /** Explicit teardown, e.g. an admin banning the leader. */
    fun disband(partyId: UUID, reason: String) {
        val members = lock.withLock {
            val party = parties[partyId] ?: return
            val snapshot = party.members.toList()
            disbandLocked(party)
            snapshot
        }
        for (member in members) {
            listeners[member]?.invoke(PartyServerMessage.Disbanded(reason))
            listeners[member]?.invoke(PartyServerMessage.State(null))
            presence.set(member, PresenceState.MENUS)
        }
    }

    /**
     * The sides this party would play as, using the leader's chosen assignment.
     * Returns null when the party cannot start — the same check [render]
     * surfaces as [PartyView.startBlockedReason], so the button and the
     * matchmaker can never disagree.
     */
    fun resolveTeams(partyId: UUID): List<List<UUID>>? = lock.withLock {
        val party = parties[partyId] ?: return null
        if (!canStartLocked(party).isNullOrEmpty()) return null
        resolveTeamsLocked(party)
    }

    /** Push a friend-request notification if that player has a socket open. */
    fun notify(player: UUID, message: PartyServerMessage) {
        listeners[player]?.invoke(message)
    }

    // --- internals (all called with the lock held unless noted) ---

    private fun disbandLocked(party: Party) {
        for (member in party.members) memberOf.remove(member)
        party.members.clear()
        parties.remove(party.id)
    }

    private fun pruneInvites(party: Party) {
        val now = clock.instant()
        party.invites.entries.removeIf { it.value.isBefore(now) }
    }

    private fun sideCountLocked(party: Party): Int = when (party.options.partyMode) {
        PartyMode.FREE_FOR_ALL -> party.members.size.coerceAtLeast(2)
        PartyMode.TEAMS, PartyMode.PARTY_VS_PARTY -> 2
    }

    /**
     * Force an options block into something this roster can actually play.
     * Chosen over rejecting: the leader adjusts the party constantly, and
     * bouncing every intermediate state back as an error is unusable.
     */
    private fun sanitize(options: PartyOptions, size: Int): PartyOptions {
        var result = options

        // Two shapes, and the format decides which. A party-only format is a
        // self-contained match the party plays against itself; anything else is
        // an open-queue mode the party enters as one side, so the party mode is
        // not the leader's to choose there.
        if (!options.format.partyOnly) {
            result = result.copy(partyMode = PartyMode.PARTY_VS_PARTY)
        } else {
            result = result.copy(
                partyMode = when (options.format) {
                    MatchFormat.PARTY_FFA -> PartyMode.FREE_FOR_ALL
                    MatchFormat.PARTY_TEAMS -> PartyMode.TEAMS
                    else -> options.partyMode
                },
            )
        }

        // Rated play needs a rated format and a shape that can be rated at all.
        // A leader hand-picking teams, or sending the sides into different
        // worlds, and calling it ranked is exactly what the ladder must refuse.
        val rateable = result.format.ranked &&
            result.partyMode == PartyMode.PARTY_VS_PARTY &&
            result.teamAssignment != TeamAssignment.MANUAL &&
            size == result.format.teamSize &&
            result.sharedWorld && result.sharedSeed
        return result.copy(ranked = result.ranked && rateable)
    }

    /**
     * Null when this party's roster and settings could start a match, otherwise
     * the reason they cannot. Deliberately says nothing about whether the party
     * is *already* queued — that is a separate, transient condition [render]
     * layers on top, so the matchmaker can still resolve teams for a party it
     * has just put in the queue.
     */
    private fun canStartLocked(party: Party): String? {
        val size = party.members.size
        val format = party.options.format
        if (!format.playable) return "pick a mode"
        return when (party.options.partyMode) {
            PartyMode.FREE_FOR_ALL ->
                if (size < 2) "invite at least one more player" else null

            PartyMode.TEAMS -> when {
                size < 2 -> "invite at least one more player"
                size % 2 != 0 -> "teams need an even number of players"
                party.options.teamAssignment == TeamAssignment.MANUAL &&
                    !manualTeamsCompleteLocked(party) -> "assign every player to a team"
                else -> null
            }

            // The party is one side of an open-queue match, so it must be
            // exactly that side's size — no bench, no missing player.
            PartyMode.PARTY_VS_PARTY -> when {
                size > format.teamSize ->
                    "${format.displayName} takes ${format.teamSize} player(s); remove ${size - format.teamSize}"
                size < format.teamSize ->
                    "${format.displayName} needs ${format.teamSize - size} more player(s)"
                else -> null
            }
        }
    }

    private fun manualTeamsCompleteLocked(party: Party): Boolean {
        if (party.manualTeams.keys != party.members.toSet()) return false
        val perSide = party.manualTeams.values.groupingBy { it }.eachCount()
        return perSide.size == 2 && perSide.values.distinct().size == 1
    }

    private fun resolveTeamsLocked(party: Party): List<List<UUID>> {
        val members = party.members.toList()
        return when (party.options.partyMode) {
            PartyMode.FREE_FOR_ALL -> TeamBalancer.freeForAll(members)

            PartyMode.PARTY_VS_PARTY -> listOf(members)

            PartyMode.TEAMS -> when (party.options.teamAssignment) {
                TeamAssignment.MANUAL -> {
                    val sides = List(2) { mutableListOf<UUID>() }
                    for (member in members) sides[party.manualTeams[member] ?: 0] += member
                    sides.map { it.toList() }
                }

                TeamAssignment.RANDOM -> TeamBalancer.random(members)

                TeamAssignment.BALANCED -> TeamBalancer.balanced(
                    members.map {
                        TeamBalancer.Rated(it, lookup.snapshot(it, party.options.format)?.rating ?: 0)
                    }
                )
            }
        }
    }

    private fun refOf(uuid: UUID): PlayerRef =
        lookup.snapshot(uuid, MatchFormat.LOCKOUT_1V1)?.ref ?: PlayerRef(uuid.toString(), "?")

    private fun render(party: Party): PartyView {
        pruneInvites(party)
        val teams = runCatching { resolveTeamsLocked(party) }.getOrNull()
        val sideOf = HashMap<UUID, Int>()
        teams?.forEachIndexed { index, side -> side.forEach { sideOf[it] = index } }

        return PartyView(
            id = party.id.toString(),
            leader = party.leader.toString(),
            members = party.members.map { uuid ->
                val snapshot = lookup.snapshot(uuid, party.options.format)
                PartyMember(
                    player = snapshot?.ref ?: PlayerRef(uuid.toString(), "?"),
                    leader = uuid == party.leader,
                    ready = uuid in party.ready,
                    // party members see each other's rating unless it is hidden;
                    // the party is a lobby, not a public profile
                    rating = snapshot?.takeIf { !it.hideRating }?.rating,
                    tier = snapshot?.tier,
                    team = sideOf[uuid],
                    presence = presence.stateOf(uuid).takeIf { it != PresenceState.OFFLINE }
                        ?: PresenceState.OFFLINE,
                )
            },
            options = party.options,
            pendingInvites = party.invites.keys.map(::refOf),
            queued = party.queued,
            maxSize = maxSize,
            startBlockedReason = if (party.queued) "already searching" else canStartLocked(party),
        )
    }

    /** Push the current state to each of [players]; never called under the lock. */
    private fun broadcast(players: Collection<UUID>) {
        if (players.isEmpty()) return
        for (player in players) {
            val view = viewFor(player)
            listeners[player]?.invoke(PartyServerMessage.State(view))
        }
    }

    private fun fireQueueCancel(partyId: UUID, members: List<UUID>, reason: String) {
        val snapshot = synchronized(queueCancelListeners) { queueCancelListeners.toList() }
        for (listener in snapshot) {
            runCatching { listener(partyId, members, reason) }
                .onFailure { log.warn("party queue-cancel listener failed", it) }
        }
    }
}
