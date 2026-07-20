package dev.yabranked.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire format mirror of dev.yabranked.proto (see the note in the agent's
 * BackendReporter: the proto module can't be nested into a mod jar yet).
 * TODO(later): publish proto as a nested-jar-capable artifact and share it.
 */

@Serializable
data class WirePlayerRef(
    val uuid: String,
    val name: String,
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
)

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
    ) : WireQueueServerMessage

    @Serializable
    @SerialName("queue_error")
    data class QueueError(val message: String) : WireQueueServerMessage
}
