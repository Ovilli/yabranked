package dev.yabranked.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Friends, parties and endorsements — the social half of the wire model.
 *
 * Split from `Model.kt` only for size; the same rules apply (every module
 * configures Json with `ignoreUnknownKeys = true` and
 * `classDiscriminator = "type"`, and new fields must carry defaults).
 */

// --- Friends ---

/** Where a player is right now, as friends see it. */
@Serializable
enum class PresenceState(val wire: String) {
    @SerialName("offline")
    OFFLINE("offline"),

    /** Signed in, sitting in the ranked menus. */
    @SerialName("menus")
    MENUS("menus"),

    @SerialName("queue")
    QUEUE("queue"),

    @SerialName("party")
    PARTY("party"),

    @SerialName("match")
    MATCH("match"),
}

@Serializable
data class Friend(
    val player: PlayerRef,
    /** [PresenceState.OFFLINE] when the friend hid their status. */
    val presence: PresenceState = PresenceState.OFFLINE,
    /** Epoch millis the friendship was accepted. */
    val since: Long = 0,
    /** Current rating, or null when hidden by their privacy settings. */
    val rating: Int? = null,
    val tier: String? = null,
    /** True when this friend is in the caller's party already. */
    val inYourParty: Boolean = false,
    /** True when the caller may invite them (their toggles allow it). */
    val invitable: Boolean = true,
)

/** A pending friend request, in whichever direction. */
@Serializable
data class FriendRequestView(
    val id: String,
    val from: PlayerRef,
    val to: PlayerRef,
    val createdAt: Long,
    /** True when the authenticated player sent it. */
    val outgoing: Boolean = false,
)

@Serializable
data class FriendListResponse(
    val friends: List<Friend> = emptyList(),
    val incoming: List<FriendRequestView> = emptyList(),
    val outgoing: List<FriendRequestView> = emptyList(),
    /** Server cap on how many friends one account may hold. */
    val maxFriends: Int = 0,
)

/**
 * Send a friend request. Either a uuid or a name — the client has a name after
 * a match but not always a uuid.
 */
@Serializable
data class FriendRequestCreate(
    val uuid: String? = null,
    val name: String? = null,
)

/**
 * Players the caller has actually played with, newest first — the only people
 * they are allowed to friend-request (see the "played once" rule).
 */
@Serializable
data class RecentPlayer(
    val player: PlayerRef,
    /** Epoch millis of the most recent shared match. */
    val lastPlayedAt: Long,
    val matchesTogether: Int = 1,
    /** Already friends, or a request is already pending. */
    val alreadyFriends: Boolean = false,
    val requestPending: Boolean = false,
    /** False when their privacy settings refuse friend requests. */
    val requestable: Boolean = true,
)

// --- Parties ---

/** How a party's teams get filled when [PartyMode.TEAMS] is selected. */
@Serializable
enum class TeamAssignment(val displayName: String) {
    /** Split so both sides' mean rating is as close as the roster allows. */
    @SerialName("balanced")
    BALANCED("Balanced by MMR"),

    @SerialName("random")
    RANDOM("Random"),

    /** Exactly what the leader set on each member. */
    @SerialName("manual")
    MANUAL("Leader picks"),
}

/**
 * The leader's choices for the party's next match. Everything here is a
 * leader-only write; members see it and nothing more.
 */
@Serializable
data class PartyOptions(
    val format: MatchFormat = MatchFormat.PARTY_FFA,
    val partyMode: PartyMode = PartyMode.FREE_FOR_ALL,
    /** All members in one world, or one world per side. */
    val sharedWorld: Boolean = true,
    /** One card seed for everyone, or one per side. */
    val sharedSeed: Boolean = true,
    val teamAssignment: TeamAssignment = TeamAssignment.BALANCED,
    /**
     * Whether the match counts toward MMR. Only honoured when the chosen
     * [format] is itself rated and the shape is fair (equal team sizes, no
     * hand-picked stacking) — the backend is the authority and will answer
     * with this forced back to false rather than silently rating a rigged
     * match.
     */
    val ranked: Boolean = false,
)

@Serializable
data class PartyMember(
    val player: PlayerRef,
    val leader: Boolean = false,
    val ready: Boolean = false,
    /** Rating in the party's selected format; null when hidden. */
    val rating: Int? = null,
    val tier: String? = null,
    /** Assigned side for [PartyMode.TEAMS], null while unassigned. */
    val team: Int? = null,
    val presence: PresenceState = PresenceState.PARTY,
)

/** The whole party as one snapshot. Pushed on every change. */
@Serializable
data class PartyView(
    val id: String,
    val leader: String,
    val members: List<PartyMember> = emptyList(),
    val options: PartyOptions = PartyOptions(),
    /** Invites sent and not yet answered. */
    val pendingInvites: List<PlayerRef> = emptyList(),
    /** The party is in the queue as a unit. */
    val queued: Boolean = false,
    val maxSize: Int = 8,
    /**
     * Why the leader cannot start right now, or null when they can. The client
     * shows this instead of an enabled Start button, so the two never disagree.
     */
    val startBlockedReason: String? = null,
) {
    val size: Int get() = members.size
}

/** An invite as the invited player sees it. */
@Serializable
data class PartyInviteView(
    val partyId: String,
    val from: PlayerRef,
    val members: List<PlayerRef> = emptyList(),
    /** Epoch millis the invite stops being acceptable. */
    val expiresAt: Long = 0,
)

// --- Party socket ---

@Serializable
sealed interface PartyClientMessage {
    /** Sent once on connect; the server answers with the current state. */
    @Serializable
    @SerialName("hello")
    data object Hello : PartyClientMessage

    /** Create a party with the caller as leader. No-op if already in one. */
    @Serializable
    @SerialName("create")
    data object Create : PartyClientMessage

    @Serializable
    @SerialName("invite")
    data class Invite(val uuid: String) : PartyClientMessage

    @Serializable
    @SerialName("accept_invite")
    data class AcceptInvite(val partyId: String) : PartyClientMessage

    @Serializable
    @SerialName("decline_invite")
    data class DeclineInvite(val partyId: String) : PartyClientMessage

    /** Leave voluntarily. From the leader this disbands the party. */
    @Serializable
    @SerialName("leave")
    data object Leave : PartyClientMessage

    @Serializable
    @SerialName("kick")
    data class Kick(val uuid: String) : PartyClientMessage

    @Serializable
    @SerialName("promote")
    data class Promote(val uuid: String) : PartyClientMessage

    @Serializable
    @SerialName("set_options")
    data class SetOptions(val options: PartyOptions) : PartyClientMessage

    /** Leader-only manual team assignment; ignored unless assignment is MANUAL. */
    @Serializable
    @SerialName("set_team")
    data class SetTeam(val uuid: String, val team: Int?) : PartyClientMessage

    @Serializable
    @SerialName("set_ready")
    data class SetReady(val ready: Boolean) : PartyClientMessage

    /** Keeps presence fresh while the player sits in a menu. */
    @Serializable
    @SerialName("ping")
    data object Ping : PartyClientMessage

    /**
     * Leader-only: start the party's own match right now.
     *
     * Only for [MatchFormat.partyOnly] formats, where the party plays against
     * itself and there is no opponent to find. Open-queue formats are started by
     * queueing as a party over the queue socket instead, since those still need
     * a matchmaker.
     */
    @Serializable
    @SerialName("start_match")
    data object StartMatch : PartyClientMessage
}

@Serializable
sealed interface PartyServerMessage {
    /** Full party state. Sent on join and after every change; never a delta. */
    @Serializable
    @SerialName("party_state")
    data class State(val party: PartyView?) : PartyServerMessage

    @Serializable
    @SerialName("party_invite")
    data class Invited(val invite: PartyInviteView) : PartyServerMessage

    /** The party the player was in no longer exists. */
    @Serializable
    @SerialName("party_disbanded")
    data class Disbanded(val reason: String) : PartyServerMessage

    /** A friend request arrived while the player was online. */
    @Serializable
    @SerialName("friend_request")
    data class FriendRequested(val request: FriendRequestView) : PartyServerMessage

    @Serializable
    @SerialName("party_error")
    data class Error(val message: String) : PartyServerMessage

    /**
     * The party's match is up; connect to it.
     *
     * Carries the same payload the queue socket sends, so the client's
     * match-found path is identical whether the match came from matchmaking or
     * from a party starting its own.
     */
    @Serializable
    @SerialName("party_match_found")
    data class MatchStarting(val match: QueueServerMessage.MatchFound) : PartyServerMessage

    /** The party tried to start and could not; the search/start is off again. */
    @Serializable
    @SerialName("party_start_failed")
    data class StartFailed(val reason: String) : PartyServerMessage
}

// --- Endorsements ---

/**
 * The catalog of endorsement categories. Fixed rather than free text: an
 * endorsement is a durable, publicly displayed signal, so the values must be
 * enumerable and un-abusable.
 */
@Serializable
enum class EndorsementCategory(val displayName: String) {
    @SerialName("shotcalling")
    SHOTCALLING("Great shotcalling"),

    @SerialName("teamwork")
    TEAMWORK("Good teammate"),

    @SerialName("sportsmanship")
    SPORTSMANSHIP("Good sport"),
}

/** Post-match endorsement of one or more teammates. */
@Serializable
data class EndorsementRequest(
    val matchId: String,
    /** Teammate uuids. Endorsing yourself or an opponent is rejected. */
    val endorsed: List<String> = emptyList(),
    val category: EndorsementCategory = EndorsementCategory.TEAMWORK,
)

/** Who the caller may still endorse for a given match. */
@Serializable
data class EndorsementPrompt(
    val matchId: String,
    val teammates: List<PlayerRef> = emptyList(),
    /** Epoch millis after which endorsing this match is refused. */
    val expiresAt: Long = 0,
    val alreadyEndorsed: Boolean = false,
)

// --- Leaderboards ---

/** One selectable leaderboard. Modes are categories so skill shows per mode. */
@Serializable
data class LeaderboardCategory(
    val id: String,
    val displayName: String,
    /** Null for aggregate boards (e.g. "endorsements"). */
    val format: MatchFormat? = null,
    val category: MatchCategory? = null,
    /** "rating" | "endorsements" | "playtime" | "streak" */
    val metric: String = "rating",
)

/** One row of a leaderboard, which is no longer always a rating board. */
@Serializable
data class LeaderboardRow(
    val rank: Int,
    val player: PlayerRef,
    /** The number this board sorts on, pre-formatted by the server. */
    val value: String,
    /** Sort key, for clients that want to draw a bar. */
    val numericValue: Long = 0,
    val tier: String? = null,
    val background: String = "default",
    val endorsementLevel: Int = 1,
    val currentStreak: Int = 0,
    val matchesPlayed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
)

@Serializable
data class LeaderboardResponse(
    val category: LeaderboardCategory,
    val rows: List<LeaderboardRow> = emptyList(),
    val season: Int = 1,
)
