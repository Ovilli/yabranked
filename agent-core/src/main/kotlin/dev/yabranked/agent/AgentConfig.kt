package dev.yabranked.agent

import dev.yabranked.proto.MatchRules
import kotlinx.serialization.json.Json
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
    /** Rules for this match, described by the backend rather than hardcoded here. */
    val rules: MatchRules,
    val playerA: ExpectedPlayer,
    val playerB: ExpectedPlayer,
    /**
     * Side-ordered rosters. A 1v1 is `[[playerA], [playerB]]`, which is exactly
     * what an orchestrator that predates team modes produces, so nothing below
     * has to special-case the two-player match.
     */
    val teams: List<List<ExpectedPlayer>>,
    /**
     * Seconds to wait for both players before voiding the match.
     *
     * Short, because this is time a player who *did* turn up spends alone in a
     * bingo lobby. It used to be five minutes, which is long enough that the
     * waiting player reads it as a broken match server rather than as a wait —
     * and the common cause is a client that died the instant it was matched,
     * which is never going to arrive however long the timer is.
     */
    val noShowTimeoutSeconds: Long,
    /**
     * Seconds between a settled match and the disconnect that ends the session.
     *
     * No longer a review window — it is a flush grace, capped by
     * `YabRankedAgent.MAX_WIND_DOWN_SECONDS`. Players used to stay on the match
     * server to read YAB's game-over screen, and that turned out to be a hazard
     * rather than a courtesy: YAB's POSTGAME ends the moment a surviving player
     * presses ready, which resets the worlds and drops everyone into a fresh lobby
     * game, and meanwhile the ranked result screen — which hangs off `DISCONNECT`
     * — could not appear at all. See `YabRankedAgent.endSessionAndShutdown`.
     *
     * The orchestrator still derives its teardown grace from this, so the
     * container outlives the process rather than being killed mid-write.
     */
    val postgameSeconds: Long,
    /**
     * Seconds between partial replay uploads; 0 records without checkpointing.
     * A container killed before it reports — a forfeit settled over the
     * player's own token, an OOM kill — then still leaves a usable recording.
     */
    val replayCheckpointSeconds: Long,
) {
    data class ExpectedPlayer(val uuid: UUID, val name: String)

    /** Everyone allowed onto this server, in side order. */
    val roster: List<ExpectedPlayer> get() = teams.flatten()

    fun playerOf(uuid: UUID): ExpectedPlayer? = roster.firstOrNull { it.uuid == uuid }

    /** Which side [uuid] plays for, or null when they are not in this match. */
    fun sideOf(uuid: UUID): Int? =
        teams.indexOfFirst { side -> side.any { it.uuid == uuid } }.takeIf { it >= 0 }

    /** Everyone on [uuid]'s side except themselves. */
    fun teammatesOf(uuid: UUID): List<ExpectedPlayer> =
        sideOf(uuid)?.let { teams[it].filter { other -> other.uuid != uuid } } ?: emptyList()

    fun opponentsOf(uuid: UUID): List<ExpectedPlayer> {
        val side = sideOf(uuid) ?: return emptyList()
        return teams.filterIndexed { index, _ -> index != side }.flatten()
    }

    /** YAB team name for each side, in the order the backend sent them. */
    fun teamNameOf(side: Int): String = TEAM_NAMES.getOrElse(side) { "team${side + 1}" }

    /** One roster entry as it crosses the container boundary. */
    @kotlinx.serialization.Serializable
    data class WirePlayer(val uuid: String, val name: String)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromEnv(env: Map<String, String> = System.getenv()): AgentConfig? {
            val backendUrl = env["YABRANKED_BACKEND_URL"] ?: return null
            val matchId = env["YABRANKED_MATCH_ID"] ?: return null
            val serverToken = env["YABRANKED_SERVER_TOKEN"] ?: return null
            val cardSeed = env["YABRANKED_CARD_SEED"]?.toLongOrNull() ?: return null
            val playerAUuid = env["YABRANKED_PLAYER_A_UUID"]?.let(::parseUuid) ?: return null
            val playerAName = env["YABRANKED_PLAYER_A_NAME"] ?: return null
            val playerBUuid = env["YABRANKED_PLAYER_B_UUID"]?.let(::parseUuid) ?: return null
            val playerBName = env["YABRANKED_PLAYER_B_NAME"] ?: return null

            // Full rosters when the backend sent them; otherwise the two-player
            // shape, so an older orchestrator keeps working unchanged.
            val teams = env["YABRANKED_TEAMS"]
                ?.let { raw ->
                    runCatching { json.decodeFromString<List<List<WirePlayer>>>(raw) }.getOrNull()
                }
                ?.mapNotNull { side ->
                    side.mapNotNull { entry ->
                        parseUuid(entry.uuid)?.let { ExpectedPlayer(it, entry.name) }
                    }.takeIf { it.size == side.size && it.isNotEmpty() }
                }
                ?.takeIf { it.size >= 2 }
                ?: listOf(
                    listOf(ExpectedPlayer(playerAUuid, playerAName)),
                    listOf(ExpectedPlayer(playerBUuid, playerBName)),
                )

            return AgentConfig(
                backendUrl = backendUrl.trimEnd('/'),
                matchId = matchId,
                serverToken = serverToken,
                cardSeed = cardSeed,
                rules = env["YABRANKED_RULES"]
                    ?.let { runCatching { json.decodeFromString<MatchRules>(it) }.getOrNull() }
                    ?: MatchRules(
                        // fall back to the original ranked format if the backend
                        // sent nothing parseable, rather than refusing to start
                        timeLimitMinutes = env["YABRANKED_TIME_LIMIT_MINUTES"]?.toIntOrNull() ?: 90,
                    ),
                playerA = ExpectedPlayer(playerAUuid, playerAName),
                playerB = ExpectedPlayer(playerBUuid, playerBName),
                teams = teams,
                noShowTimeoutSeconds = env["YABRANKED_NO_SHOW_TIMEOUT_SECONDS"]?.toLongOrNull() ?: 90,
                postgameSeconds = env["YABRANKED_POSTGAME_SECONDS"]?.toLongOrNull() ?: 20,
                replayCheckpointSeconds =
                    env["YABRANKED_REPLAY_CHECKPOINT_SECONDS"]?.toLongOrNull()?.coerceAtLeast(0) ?: 60,
            )
        }

        private fun parseUuid(value: String): UUID? =
            runCatching { UUID.fromString(value) }.getOrNull()

        /**
         * YAB's team names, in side order. The first two keep the colours the
         * 1v1 path has always used, so existing behaviour is unchanged.
         */
        private val TEAM_NAMES = listOf("red", "blue", "green", "yellow", "aqua", "purple", "orange", "white")
    }
}
