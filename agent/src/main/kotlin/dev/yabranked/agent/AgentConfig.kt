package dev.yabranked.agent

import java.util.UUID

/**
 * Match parameters injected by the orchestrator via container environment.
 * The agent refuses to activate if any required variable is missing, so a
 * plain (non-ranked) server with this mod installed stays inert.
 */
data class AgentConfig(
    val backendUrl: String,
    val matchId: String,
    /** Per-match secret used to authenticate reports to the backend. */
    val serverToken: String,
    val cardSeed: Long,
    val timeLimitMinutes: Int,
    val playerA: ExpectedPlayer,
    val playerB: ExpectedPlayer,
    /** Seconds to wait for both players before voiding the match. */
    val noShowTimeoutSeconds: Long,
) {
    data class ExpectedPlayer(val uuid: UUID, val name: String)

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): AgentConfig? {
            val backendUrl = env["YABRANKED_BACKEND_URL"] ?: return null
            val matchId = env["YABRANKED_MATCH_ID"] ?: return null
            val serverToken = env["YABRANKED_SERVER_TOKEN"] ?: return null
            val cardSeed = env["YABRANKED_CARD_SEED"]?.toLongOrNull() ?: return null
            val playerAUuid = env["YABRANKED_PLAYER_A_UUID"]?.let(::parseUuid) ?: return null
            val playerAName = env["YABRANKED_PLAYER_A_NAME"] ?: return null
            val playerBUuid = env["YABRANKED_PLAYER_B_UUID"]?.let(::parseUuid) ?: return null
            val playerBName = env["YABRANKED_PLAYER_B_NAME"] ?: return null

            return AgentConfig(
                backendUrl = backendUrl.trimEnd('/'),
                matchId = matchId,
                serverToken = serverToken,
                cardSeed = cardSeed,
                timeLimitMinutes = env["YABRANKED_TIME_LIMIT_MINUTES"]?.toIntOrNull() ?: 90,
                playerA = ExpectedPlayer(playerAUuid, playerAName),
                playerB = ExpectedPlayer(playerBUuid, playerBName),
                noShowTimeoutSeconds = env["YABRANKED_NO_SHOW_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 300,
            )
        }

        private fun parseUuid(value: String): UUID? =
            runCatching { UUID.fromString(value) }.getOrNull()
    }
}
