package dev.yabranked.proto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Shared data model between the backend, the match-server agent mod,
 * and the client mod. Everything here is kotlinx.serialization JSON.
 *
 * UUIDs are serialized as strings (Mojang dashed format) for
 * compatibility with vanilla tooling and YAB's UuidAsString.
 */

/**
 * The rules a match server should apply, as data rather than code.
 *
 * The agent turns this into YAB commands, so adding a format is a matter of
 * describing it here — no agent changes, no new image.
 */
@Serializable
data class MatchRules(
    /** Claiming an item denies it to the opponent. */
    val lockout: Boolean = true,
    /** Items must remain in the player's inventory to count. */
    val inventory: Boolean = false,
    /** Objectives stay hidden until discovered. */
    val hiddenItems: Boolean = false,
    /** Claimed items are taken from the player. */
    val consumeItems: Boolean = false,
    /** "lines" or "items". */
    val goalType: String = "lines",
    val goalCount: Int = 1,
    val timeLimitMinutes: Int = 90,
    val pvp: Boolean = true,
    /** Tier distribution S,A,B,C,D — must total 25. Null keeps the server default. */
    val difficulty: List<Int>? = null,
)

/**
 * Broad grouping used by the client's mode picker and by the per-mode
 * leaderboard categories. Solo ladders and team ladders are rated separately,
 * so a player who never queues 1v1 can still be top of the 3v3 board.
 */
@Serializable
enum class MatchCategory(val displayName: String) {
    @SerialName("solo")
    SOLO("Solo"),

    @SerialName("team")
    TEAM("Team"),

    @SerialName("party")
    PARTY("Party"),
}

/**
 * How a party's players are split into sides for a custom match.
 *
 * A party can play itself (every member on their own side, or split into
 * teams) or be matched against another party of the same size.
 */
@Serializable
enum class PartyMode(val displayName: String) {
    /** Every member on their own side: N-way free-for-all inside the party. */
    @SerialName("free_for_all")
    FREE_FOR_ALL("Free-for-all"),

    /** The party splits into two teams that fight each other. */
    @SerialName("teams")
    TEAMS("Teams"),

    /** The whole party plays as one side against a second party of equal size. */
    @SerialName("party_vs_party")
    PARTY_VS_PARTY("Party vs Party"),
}

/**
 * A playable mode.
 *
 * [teamSize] × [teamCount] is the number of players a match needs; solo modes
 * are 1×2. [partyOnly] modes are never open-queued — they are only reachable
 * through a party, because their shape is chosen by the party leader.
 *
 * Serialized by [MatchFormatSerializer] rather than the generated enum
 * serializer: an unknown name decodes to [UNSUPPORTED] instead of failing the
 * whole frame, so adding a mode no longer breaks every older client. Older
 * clients simply cannot select what they cannot name.
 */
@Serializable(with = MatchFormatSerializer::class)
enum class MatchFormat(
    /** Stable wire name. Never change one that has shipped. */
    val wire: String,
    val displayName: String,
    /** Rated formats affect MMR; casual ones do not. */
    val ranked: Boolean,
    val rules: MatchRules,
    /** Players per side. */
    val teamSize: Int = 1,
    /** Number of sides. Two for everything except party free-for-all. */
    val teamCount: Int = 2,
    val category: MatchCategory = MatchCategory.SOLO,
    /** Only reachable from a party; never offered in the open queue. */
    val partyOnly: Boolean = false,
) {
    @SerialName("lockout_1v1")
    LOCKOUT_1V1(
        wire = "lockout_1v1",
        displayName = "Lockout 1v1",
        ranked = true,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1),
    ),

    @SerialName("ranked_2v2")
    RANKED_2V2(
        wire = "ranked_2v2",
        displayName = "Ranked 2v2",
        ranked = true,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1, timeLimitMinutes = 100),
        teamSize = 2,
        category = MatchCategory.TEAM,
    ),

    @SerialName("ranked_3v3")
    RANKED_3V3(
        wire = "ranked_3v3",
        displayName = "Ranked 3v3",
        ranked = true,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 2, timeLimitMinutes = 110),
        teamSize = 3,
        category = MatchCategory.TEAM,
    ),

    @SerialName("ranked_4v4")
    RANKED_4V4(
        wire = "ranked_4v4",
        displayName = "Ranked 4v4",
        ranked = true,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 2, timeLimitMinutes = 120),
        teamSize = 4,
        category = MatchCategory.TEAM,
    ),

    @SerialName("casual_lockout")
    CASUAL_LOCKOUT(
        wire = "casual_lockout",
        displayName = "Casual Lockout",
        ranked = false,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1),
    ),

    @SerialName("casual_standard")
    CASUAL_STANDARD(
        wire = "casual_standard",
        displayName = "Casual Standard",
        ranked = false,
        // no lockout: both players race the same card without denial
        rules = MatchRules(lockout = false, goalType = "lines", goalCount = 1),
    ),

    @SerialName("casual_blackout")
    CASUAL_BLACKOUT(
        wire = "casual_blackout",
        displayName = "Casual Blackout",
        ranked = false,
        rules = MatchRules(lockout = true, goalType = "items", goalCount = 25, timeLimitMinutes = 120),
    ),

    @SerialName("casual_hidden")
    CASUAL_HIDDEN(
        wire = "casual_hidden",
        displayName = "Casual Hidden Items",
        ranked = false,
        rules = MatchRules(lockout = true, hiddenItems = true, goalType = "lines", goalCount = 1),
    ),

    @SerialName("casual_2v2")
    CASUAL_2V2(
        wire = "casual_2v2",
        displayName = "Casual 2v2",
        ranked = false,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1, timeLimitMinutes = 100),
        teamSize = 2,
        category = MatchCategory.TEAM,
    ),

    /**
     * Party free-for-all: every member on their own side. [teamCount] is a
     * placeholder — the real side count is the party's size, resolved when the
     * match is built.
     */
    @SerialName("party_ffa")
    PARTY_FFA(
        wire = "party_ffa",
        displayName = "Party Free-for-all",
        ranked = false,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1),
        teamSize = 1,
        teamCount = 2,
        category = MatchCategory.PARTY,
        partyOnly = true,
    ),

    /** Party split into two teams. Team sizes come from the party's roster. */
    @SerialName("party_teams")
    PARTY_TEAMS(
        wire = "party_teams",
        displayName = "Party Teams",
        ranked = false,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1),
        teamSize = 2,
        category = MatchCategory.PARTY,
        partyOnly = true,
    ),

    /**
     * A format this build does not know. Only ever produced by decoding: it is
     * never offered, never queueable, and the client renders it as "Unknown
     * mode" rather than dropping the message it arrived in.
     */
    @SerialName("unsupported")
    UNSUPPORTED(
        wire = "unsupported",
        displayName = "Unknown mode",
        ranked = false,
        rules = MatchRules(),
    );

    /** Total players a full match of this format needs. */
    val playerCount: Int get() = teamSize * teamCount

    /**
     * Teammates exist, so post-match endorsements are meaningful. Solo modes
     * have nobody to endorse and never collect them.
     */
    val endorsable: Boolean get() = teamSize > 1

    /** Selectable in a UI. [UNSUPPORTED] never is. */
    val playable: Boolean get() = this != UNSUPPORTED

    companion object {
        val rankedFormats get() = entries.filter { it.ranked && it.playable }
        val casualFormats get() = entries.filter { !it.ranked && it.playable && !it.partyOnly }

        /** Modes the open queue offers — everything a party is not required for. */
        val queueableFormats get() = entries.filter { it.playable && !it.partyOnly }

        val partyFormats get() = entries.filter { it.partyOnly }

        fun byWire(wire: String): MatchFormat? = entries.firstOrNull { it.wire == wire }

        /**
         * Enum-name lookup for persisted values (the database stores the Kotlin
         * name). Unknown names decode to [UNSUPPORTED] rather than throwing, so
         * a row written by a newer backend cannot crash an older read path.
         */
        fun byName(name: String): MatchFormat =
            entries.firstOrNull { it.name == name } ?: UNSUPPORTED
    }
}

/** See [MatchFormat]: unknown wire names decode to [MatchFormat.UNSUPPORTED]. */
object MatchFormatSerializer : KSerializer<MatchFormat> {
    override val descriptor =
        PrimitiveSerialDescriptor("dev.yabranked.proto.MatchFormat", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: MatchFormat) = encoder.encodeString(value.wire)

    override fun deserialize(decoder: Decoder): MatchFormat =
        MatchFormat.byWire(decoder.decodeString()) ?: MatchFormat.UNSUPPORTED
}

@Serializable
data class PlayerRef(
    /** Mojang account UUID, dashed string form. */
    val uuid: String,
    val name: String,
    /**
     * ISO 3166-1 alpha-2 country code (lowercase), null if unset or hidden by
     * the referenced player's flag-privacy setting.
     */
    val country: String? = null,
)

/**
 * Who may see one profile field.
 *
 * Every visibility-gated field on [PlayerProfile] is redacted to null (or a
 * zero) for a viewer the setting excludes — the field is never simply omitted,
 * so an older client that cannot see the setting still renders a placeholder
 * rather than a stale value.
 */
@Serializable
enum class Visibility {
    @SerialName("everyone")
    EVERYONE,

    @SerialName("friends")
    FRIENDS,

    @SerialName("nobody")
    NOBODY;

    /** Whether a viewer with this relationship may see the field. */
    fun allows(isSelf: Boolean, isFriend: Boolean): Boolean = when (this) {
        EVERYONE -> true
        FRIENDS -> isSelf || isFriend
        NOBODY -> isSelf
    }
}

/**
 * Per-player privacy. Defaults are permissive so behaviour is unchanged for
 * accounts that never open the settings screen — except that both social
 * toggles start *on*, matching the pre-social behaviour of "anyone may invite".
 */
@Serializable
data class PrivacySettings(
    /** Off means incoming friend requests are refused outright. */
    val allowFriendRequests: Boolean = true,
    /** Off means party invites are refused, even from friends. */
    val allowPartyInvites: Boolean = true,
    /** Off restricts party invites to accepted friends. */
    val partyInvitesFromFriendsOnly: Boolean = false,
    val showCountry: Visibility = Visibility.EVERYONE,
    val showRating: Visibility = Visibility.EVERYONE,
    val showPlaytime: Visibility = Visibility.EVERYONE,
    val showMatchHistory: Visibility = Visibility.EVERYONE,
    val showAchievements: Visibility = Visibility.EVERYONE,
    val showEndorsements: Visibility = Visibility.EVERYONE,
    val showStreak: Visibility = Visibility.EVERYONE,
    /** Whether friends' clients see "in menus / in queue / in match". */
    val showOnlineStatus: Visibility = Visibility.FRIENDS,
)

/**
 * One mode's counters for a player. Kept per [MatchFormat] so the profile can
 * show where the playtime actually went, and so the leaderboard can offer a
 * category per mode.
 */
@Serializable
data class ModeStats(
    val format: MatchFormat,
    val matchesPlayed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val playtimeSeconds: Long = 0,
    /** Rating in this mode's ladder; null for unrated modes. */
    val rating: Int? = null,
    val tier: String? = null,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
)

/**
 * Teammate endorsements. [level] is derived from [total] via a fixed ladder
 * (see the backend's `Endorsements` catalog); [progress] is 0..1 through the
 * current level, so the client needs no copy of the thresholds.
 */
@Serializable
data class EndorsementSummary(
    val level: Int = 1,
    val total: Int = 0,
    /** 0.0..1.0 through the current level; 1.0 at the cap. */
    val progress: Float = 0f,
    /** Per-category counts, e.g. "shotcalling" -> 12. */
    val categories: Map<String, Int> = emptyMap(),
)

@Serializable
data class PlayerProfile(
    val uuid: String,
    val name: String,
    val rating: Int,
    val placementMatchesRemaining: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    /** Display tier, e.g. "Gold II" or "Unranked" during placements. */
    val tier: String = "Unranked",
    val season: Int = 1,
    /** 1-based leaderboard position, null until on the ladder. */
    val rank: Int? = null,
    /** ISO 3166-1 alpha-2 country code (lowercase), null if the player set none. */
    val country: String? = null,
    /** Profile-card background id; "default" when unset. */
    val background: String = "default",
    /** Total seconds spent in counted matches this season. */
    val playtimeSeconds: Long = 0,
    /** Matches this player forfeited (concede or no-show) this season. */
    val forfeits: Int = 0,
    /** Highest rating reached this season (true peak, not a windowed estimate). */
    val peakRating: Int? = null,
    /**
     * This player's privacy preferences, echoed back so their own client can
     * restore the toggle state. On other players' public profiles these also
     * signal that [country]/[rating] were redacted, so the viewer renders a
     * placeholder instead of a stale value.
     */
    val hideFlag: Boolean = false,
    val hideRating: Boolean = false,
    /**
     * Full privacy preferences. [hideFlag]/[hideRating] remain the wire form
     * older clients understand and are kept in sync with
     * [PrivacySettings.showCountry]/[PrivacySettings.showRating].
     */
    val privacy: PrivacySettings = PrivacySettings(),
    /** Per-mode counters, only for modes this player has actually played. */
    val modes: List<ModeStats> = emptyList(),
    /**
     * Consecutive wins in rated play right now. Shown on the profile and on the
     * versus screen, so an opponent can read form as well as rating. Null when
     * the viewer's privacy settings hide it.
     */
    val currentStreak: Int? = 0,
    val bestStreak: Int? = 0,
    val endorsement: EndorsementSummary? = EndorsementSummary(),
    /** "offline" | "menus" | "queue" | "match"; null when hidden from this viewer. */
    val onlineStatus: String? = null,
    /** Whether the viewer and this player are accepted friends. */
    val isFriend: Boolean = false,
) {
    val isPlaced: Boolean get() = placementMatchesRemaining <= 0
}

/** Fields a player may edit on their own profile. Null means "leave unchanged". */
@Serializable
data class ProfileUpdate(
    val country: String? = null,
    val background: String? = null,
    /** Hide the country flag from other players. Null leaves it unchanged. */
    val hideFlag: Boolean? = null,
    /** Hide the exact rating on the public profile and match-found reveal. */
    val hideRating: Boolean? = null,
    /**
     * Whole privacy block. When present it wins over [hideFlag]/[hideRating],
     * which stay for clients that predate it.
     */
    val privacy: PrivacySettings? = null,
)

/** Credentials a client presents to open a ranked session. */
@Serializable
data class SessionRequest(
    val username: String,
    val serverId: String,
    val clientVersion: String? = null,
)

/** A minted session token plus the authenticated player's own profile. */
@Serializable
data class SessionResponse(
    val token: String,
    val profile: PlayerProfile,
)

/**
 * A milestone a player has unlocked (first win, a rating tier, a win streak,
 * playtime, …). Title and description come from the server's catalog so the
 * client needs no local copy of the achievement definitions.
 */
@Serializable
data class Achievement(
    val id: String,
    val title: String,
    val description: String,
    /** Epoch millis the milestone was first reached. */
    val earnedAt: Long,
)

/** One row of a player's match history, from that player's perspective. */
@Serializable
data class MatchHistoryEntry(
    val matchId: String,
    val opponent: PlayerRef,
    /** "win" | "loss" | "draw" | "void" */
    val result: String,
    val ratingBefore: Int,
    val ratingAfter: Int?,
    /** Opponent's rating going into this match, for best-win / avg-opponent stats. */
    val opponentRating: Int? = null,
    val durationSeconds: Long?,
    /** Epoch seconds of match completion (null if still running). */
    val completedAt: Long?,
    /** Objective counts from this player's / the opponent's perspective. */
    val yourScore: Int? = null,
    val opponentScore: Int? = null,
    /** Match world/card seeds, for the detail view. */
    val worldSeed: Long? = null,
    val cardSeed: Long? = null,
    /** Whether the match ended on a forfeit, and whether this player was the one. */
    val wasForfeit: Boolean = false,
    val forfeitedByYou: Boolean = false,
    /** Which mode this was; history is now mixed across solo, team and party. */
    val format: MatchFormat = MatchFormat.LOCKOUT_1V1,
    /**
     * Whether this match could move a rating at all.
     *
     * Not the same question as [format]`.ranked`: a party may play a rated
     * format unrated. The client needs it because an unrated match still has a
     * [ratingBefore], and showing a delta against it — even a zero one — reads
     * as "your MMR moved" to someone who deliberately queued casual.
     */
    val rated: Boolean = true,
    /** This player's teammates, excluding themselves. Empty in solo modes. */
    val teammates: List<PlayerRef> = emptyList(),
    /** Every opposing player. [opponent] is the first of these. */
    val opponents: List<PlayerRef> = emptyList(),
    /**
     * Whether this match is still inside the endorsement window and the caller
     * has not endorsed yet — the client only offers the endorse prompt then.
     */
    val canEndorse: Boolean = false,
)

/**
 * A misconduct report.
 *
 * One of [matchId] or [accused] must be present. [matchId] is the post-match
 * path, where the match being reported is unambiguous. [accused] is the profile
 * path — the player is looking at someone they played earlier and has no match
 * id to hand — and the backend resolves it to the most recent decided match the
 * two shared. The accused is always derived server-side from the match roster,
 * so nothing here lets a client pin a report on someone it never played.
 */
@Serializable
data class ReportRequest(
    val matchId: String? = null,
    val reason: String,
    /** UUID of the player being reported; required when [matchId] is absent. */
    val accused: String? = null,
)

/** Head-to-head record, from the first player's perspective. */
@Serializable
data class VersusRecord(
    val wins: Int,
    val losses: Int,
    val draws: Int,
) {
    val played: Int get() = wins + losses + draws
}

@Serializable
enum class MatchTeam {
    @SerialName("team_a")
    TEAM_A,

    @SerialName("team_b")
    TEAM_B,
}

/**
 * One side of a match as the client renders it: the roster plus the numbers
 * the versus screen shows. Ratings are already redacted per the referenced
 * players' privacy settings by the time this leaves the backend.
 */
@Serializable
data class MatchSide(
    val index: Int,
    val players: List<PlayerRef> = emptyList(),
    /** Per-player rating, index-aligned with [players]; 0 where hidden. */
    val ratings: List<Int> = emptyList(),
    /** Per-player tier string, index-aligned with [players]. */
    val tiers: List<String> = emptyList(),
    /** Mean rating of the side, which is what team matchmaking balanced on. */
    val averageRating: Int = 0,
    /** Display label; "Team A"/"Team B" for two sides, player name for a FFA side. */
    val label: String = "",
)

@Serializable
enum class MatchOutcome {
    @SerialName("team_a")
    TEAM_A_WIN,

    @SerialName("team_b")
    TEAM_B_WIN,

    @SerialName("draw")
    DRAW,

    /** Match never completed (server crash, both abandoned); no rating change. */
    @SerialName("void")
    VOID,
}

/**
 * Settings the backend generates for a match. The agent mod applies these
 * to the YAB server before letting players in; clients never influence them.
 */
@Serializable
data class MatchSettings(
    val format: MatchFormat,
    /** Seed for the Minecraft world (level.dat). */
    val worldSeed: Long,
    /** Seed passed to `/bingo card seed <long>` / CardService.generate. */
    val cardSeed: Long,
    /** Time limit in seconds; the agent configures `/bingo timelimit`. */
    val timeLimitSeconds: Long,
    /**
     * All sides share one world. When false each side is given its own world —
     * a party-leader choice, and the reason [perTeamWorldSeeds] exists.
     */
    val sharedWorld: Boolean = true,
    /**
     * Every side races the same card. When false each side gets its own card
     * seed from [perTeamCardSeeds].
     */
    val sharedSeed: Boolean = true,
    /** Per-side world seeds, used only when [sharedWorld] is false. */
    val perTeamWorldSeeds: List<Long> = emptyList(),
    /** Per-side card seeds, used only when [sharedSeed] is false. */
    val perTeamCardSeeds: List<Long> = emptyList(),
    /** Number of sides in this match; 2 for everything but a party free-for-all. */
    val teamCount: Int = 2,
)

/**
 * The roster the agent gates joins on.
 *
 * [teams] is side-ordered: index 0 is team A, 1 is team B, and a party
 * free-for-all has one entry per player. [teamA]/[teamB] stay as the two-side
 * view older agents read.
 */
@Serializable
data class MatchAssignment(
    val matchId: String,
    val settings: MatchSettings,
    val teamA: PlayerRef,
    val teamB: PlayerRef,
    val teams: List<List<PlayerRef>> = listOf(listOf(teamA), listOf(teamB)),
)

/**
 * Result report sent by the match-server agent to the backend.
 * Authenticated with the per-match server token, not by any client.
 */
@Serializable
data class MatchResultReport(
    val matchId: String,
    val outcome: MatchOutcome,
    val durationSeconds: Long,
    /** Final objective counts, for match history display. */
    val teamAScore: Int,
    val teamBScore: Int,
    /** UUID of the player who forfeited (concede or no-show), null for a normal finish. */
    val forfeitedBy: String? = null,
    /**
     * Winning side index for matches with more than two sides. Null on a
     * two-side match, where [outcome] already says it. A draw or a void is
     * still carried by [outcome] in both cases.
     */
    val winningTeam: Int? = null,
    /**
     * Side-ordered final scores, for matches with more than two sides.
     * [teamAScore]/[teamBScore] stay authoritative for two-side matches.
     */
    val teamScores: List<Int> = emptyList(),
)

// --- Queue protocol (client <-> backend WebSocket) ---

@Serializable
sealed interface QueueClientMessage {
    @Serializable
    @SerialName("join_queue")
    data class JoinQueue(
        val format: MatchFormat,
        /**
         * Queue the caller's whole party as one unit rather than the caller
         * alone. Only the party leader may set this; anyone else is refused
         * with a [QueueServerMessage.QueueError] rather than silently queued
         * solo, which would split the party across two matches.
         */
        val asParty: Boolean = false,
    ) : QueueClientMessage

    @Serializable
    @SerialName("leave_queue")
    data object LeaveQueue : QueueClientMessage
}

@Serializable
sealed interface QueueServerMessage {
    @Serializable
    @SerialName("queue_state")
    data class QueueState(
        val position: Int,
        val playersInQueue: Int,
        val waitedSeconds: Long,
        /**
         * The player has been paired and the match server is being provisioned
         * — which takes up to a minute. Without this the client kept rendering
         * the last "searching" state with a frozen timer for that whole window,
         * because pairing dequeues the player and the state pushes stopped.
         *
         * A defaulted field rather than a new [QueueServerMessage] subtype: an
         * unknown subtype name fails to decode outright on older clients, while
         * an unknown field is ignored.
         */
        val preparingMatch: Boolean = false,
        /**
         * Seconds until the soonest pairing the matchmaker's current bands
         * allow, or null when it cannot be derived (nobody in range queued).
         * The client used to guess this from the headcount and would happily
         * say "any moment" to someone no queued opponent could match.
         */
        val etaSeconds: Long? = null,
    ) : QueueServerMessage

    @Serializable
    @SerialName("match_found")
    data class MatchFound(
        val matchId: String,
        /** Which side of the match record this player is on. */
        val team: MatchTeam,
        val opponent: PlayerRef,
        /** Where to connect once the match server reports ready. */
        val serverAddress: String,
        /** Opponent's rating and tier, for the match-found reveal. */
        val opponentRating: Int = 0,
        val opponentTier: String = "Unranked",
        /**
         * Full rosters for team and party matches, side-ordered. [opponent] is
         * the first player of the other side, kept so a client that predates
         * team modes still renders something sensible for a 1v1.
         */
        val teams: List<MatchSide> = emptyList(),
        /** Which entry of [teams] this player is on. */
        val teamIndex: Int = 0,
        val format: MatchFormat = MatchFormat.LOCKOUT_1V1,
        /** Opponent's current win streak, for the versus screen. Null when hidden. */
        val opponentStreak: Int? = null,
    ) : QueueServerMessage

    /**
     * Someone in the caller's party changed something that affects the queue —
     * the leader left, a member dropped below the format's size, the leader
     * cancelled. The client stops showing a search rather than waiting forever.
     */
    @Serializable
    @SerialName("queue_cancelled")
    data class QueueCancelled(val reason: String) : QueueServerMessage

    @Serializable
    @SerialName("queue_error")
    data class QueueError(val message: String) : QueueServerMessage
}
