package dev.yabranked.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format mirror of dev.yabranked.proto (see the note in the agent's
 * BackendReporter: the proto module can't be nested into a mod jar yet).
 * TODO(later): publish proto as a nested-jar-capable artifact and share it.
 */

/**
 * Client-side mirror of the queueable formats in dev.yabranked.proto.MatchFormat.
 * Kept in sync by hand until the proto module can be shared with the mod jar.
 */
data class WireFormat(
    val id: String,
    val displayName: String,
    /** Rated formats affect MMR; casual ones do not. */
    val ranked: Boolean,
) {
    companion object {
        val all = listOf(
            WireFormat("lockout_1v1", "Lockout 1v1", ranked = true),
            WireFormat("casual_lockout", "Casual Lockout", ranked = false),
            WireFormat("casual_standard", "Casual Standard", ranked = false),
            WireFormat("casual_blackout", "Casual Blackout", ranked = false),
            WireFormat("casual_hidden", "Casual Hidden Items", ranked = false),
        )
        val default = all.first()
    }
}

@Serializable
data class WirePlayerRef(
    val uuid: String,
    val name: String,
    /** ISO 3166-1 alpha-2 country code (lowercase), null if unset. */
    val country: String? = null,
)

@Serializable
data class WireProfile(
    val uuid: String,
    val name: String,
    val rating: Int,
    val placementMatchesRemaining: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val tier: String = "Unranked",
    val season: Int = 1,
    val rank: Int? = null,
    /** ISO 3166-1 alpha-2 country code (lowercase), null if the player set none. */
    val country: String? = null,
    /** Profile-card background id; "default" when unset. */
    val background: String = "default",
    /** Total seconds spent in counted matches this season. */
    val playtimeSeconds: Long = 0,
    /** Matches this player forfeited this season. */
    val forfeits: Int = 0,
)

/** Self-profile edit. A field left null is unchanged; country "" clears the flag. */
@Serializable
data class WireProfileUpdate(
    val country: String? = null,
    val background: String? = null,
)

@Serializable
data class WireHistoryEntry(
    val matchId: String,
    val opponent: WirePlayerRef,
    val result: String,
    val ratingBefore: Int,
    val ratingAfter: Int?,
    val durationSeconds: Long?,
    val completedAt: Long?,
)

@Serializable
data class WireReportRequest(
    val matchId: String,
    val reason: String,
)

@Serializable
data class WireVersusRecord(
    val wins: Int,
    val losses: Int,
    val draws: Int,
) {
    val played: Int get() = wins + losses + draws
}

@Serializable
data class WireSessionRequest(
    val username: String,
    val serverId: String,
    val clientVersion: String? = null,
)

@Serializable
data class WireSessionResponse(
    val token: String,
    val profile: WireProfile,
)

@Serializable
sealed interface WireQueueClientMessage {
    @Serializable
    @SerialName("join_queue")
    data class JoinQueue(val format: String) : WireQueueClientMessage

    @Serializable
    @SerialName("leave_queue")
    data object LeaveQueue : WireQueueClientMessage
}

@Serializable
sealed interface WireQueueServerMessage {
    @Serializable
    @SerialName("queue_state")
    data class QueueState(
        val position: Int,
        val playersInQueue: Int,
        val waitedSeconds: Long,
    ) : WireQueueServerMessage

    @Serializable
    @SerialName("match_found")
    data class MatchFound(
        val matchId: String,
        val team: String,
        val opponent: WirePlayerRef,
        val serverAddress: String,
        val opponentRating: Int = 0,
        val opponentTier: String = "Unranked",
    ) : WireQueueServerMessage

    @Serializable
    @SerialName("queue_error")
    data class QueueError(val message: String) : WireQueueServerMessage
}
