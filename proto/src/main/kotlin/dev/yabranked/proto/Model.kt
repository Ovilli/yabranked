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

@Serializable
enum class MatchFormat {
    /** First ranked format: two teams of one, lockout card, first win ends the game. */
    @SerialName("lockout_1v1")
    LOCKOUT_1V1,
}

@Serializable
data class PlayerRef(
    /** Mojang account UUID, dashed string form. */
    val uuid: String,
    val name: String,
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
) {
    val isPlaced: Boolean get() = placementMatchesRemaining <= 0
}

/** One row of a player's match history, from that player's perspective. */
@Serializable
data class MatchHistoryEntry(
    val matchId: String,
    val opponent: PlayerRef,
    /** "win" | "loss" | "draw" | "void" */
    val result: String,
    val ratingBefore: Int,
    val ratingAfter: Int?,
    val durationSeconds: Long?,
    /** Epoch seconds of match completion (null if still running). */
    val completedAt: Long?,
)

@Serializable
data class ReportRequest(
    val matchId: String,
    val reason: String,
)

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
    ) : QueueServerMessage

    @Serializable
    @SerialName("queue_error")
    data class QueueError(val message: String) : QueueServerMessage
}
