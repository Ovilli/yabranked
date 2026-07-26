package dev.yabranked.proto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
enum class MatchFormat(
    val displayName: String,
    /** Rated formats affect MMR; casual ones do not. */
    val ranked: Boolean,
    val rules: MatchRules,
) {
    @SerialName("lockout_1v1")
    LOCKOUT_1V1(
        displayName = "Lockout 1v1",
        ranked = true,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1),
    ),

    @SerialName("casual_lockout")
    CASUAL_LOCKOUT(
        displayName = "Casual Lockout",
        ranked = false,
        rules = MatchRules(lockout = true, goalType = "lines", goalCount = 1),
    ),

    @SerialName("casual_standard")
    CASUAL_STANDARD(
        displayName = "Casual Standard",
        ranked = false,
        // no lockout: both players race the same card without denial
        rules = MatchRules(lockout = false, goalType = "lines", goalCount = 1),
    ),

    @SerialName("casual_blackout")
    CASUAL_BLACKOUT(
        displayName = "Casual Blackout",
        ranked = false,
        rules = MatchRules(lockout = true, goalType = "items", goalCount = 25, timeLimitMinutes = 120),
    ),

    @SerialName("casual_hidden")
    CASUAL_HIDDEN(
        displayName = "Casual Hidden Items",
        ranked = false,
        rules = MatchRules(lockout = true, hiddenItems = true, goalType = "lines", goalCount = 1),
    );

    companion object {
        val rankedFormats get() = entries.filter { it.ranked }
        val casualFormats get() = entries.filter { !it.ranked }
    }
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
)

@Serializable
data class ReportRequest(
    val matchId: String,
    val reason: String,
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
)

@Serializable
data class MatchAssignment(
    val matchId: String,
    val settings: MatchSettings,
    val teamA: PlayerRef,
    val teamB: PlayerRef,
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
)

// --- Queue protocol (client <-> backend WebSocket) ---

@Serializable
sealed interface QueueClientMessage {
    @Serializable
    @SerialName("join_queue")
    data class JoinQueue(val format: MatchFormat) : QueueClientMessage

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
    ) : QueueServerMessage

    @Serializable
    @SerialName("queue_error")
    data class QueueError(val message: String) : QueueServerMessage
}
