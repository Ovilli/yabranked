package dev.yabranked.backend.orchestrator

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class OrchestratorConfig(
    /** Docker image for the match server (see docker/ in this repo). */
    val image: String,
    /** Host players connect to, e.g. the server's public IP or "localhost". */
    val publicHost: String,
    /** Backend URL reachable from inside the container (host network ⇒ localhost works). */
    val backendUrlForAgents: String,
    val portRangeStart: Int = 25600,
    val portRangeSize: Int = 50,
    /** Vanilla online-mode for match servers; disable only for local testing. */
    val onlineMode: Boolean = true,
    /**
     * Host networking gives the cleanest setup on a bare-metal Docker engine.
     * Docker Desktop runs containers in a VM — set false there to publish
     * ports instead (the agent then reaches the backend via host.docker.internal).
     */
    val hostNetwork: Boolean = true,
    /** Optional override for the agent's no-show timeout (seconds). */
    val noShowTimeoutSeconds: Long? = null,
    /** Optional override for how long the server lingers after a match (seconds). */
    val postgameSeconds: Long? = null,
    /** How long a match may stay PENDING before it is voided and reaped. */
    val readyTimeout: Duration = 10.minutes,
)

/**
 * Provisions one ephemeral Docker match server per created match and tears
 * it down once the match settles (or never becomes ready in time).
 */
class MatchOrchestrator(
    private val config: OrchestratorConfig,
    private val runtime: ContainerRuntime,
    private val matchService: MatchService,
    private val matches: MatchStore,
    private val players: PlayerStore,
) {
    private val log = LoggerFactory.getLogger("orchestrator")
    private val portCounter = AtomicInteger(0)
    private val containers = ConcurrentHashMap<String, String>() // matchId -> container name

    fun start(scope: CoroutineScope) {
        matchService.onMatchCreated { match ->
            scope.launch(Dispatchers.IO) { provision(scope, match) }
        }
        matchService.onMatchSettled { match ->
            scope.launch(Dispatchers.IO) { teardown(match.id.toString()) }
        }
    }

    private fun nextPort(): Int =
        config.portRangeStart + (portCounter.getAndIncrement() % config.portRangeSize)

    private fun provision(scope: CoroutineScope, match: MatchRecord) {
        val port = nextPort()
        val name = "yabranked-${match.id}"
        val playerA = players.getPlayer(match.playerA) ?: error("unknown player ${match.playerA}")
        val playerB = players.getPlayer(match.playerB) ?: error("unknown player ${match.playerB}")

        val containerPort = if (config.hostNetwork) port else 25565
        val env = buildMap {
            put("SERVER_PORT", containerPort.toString())
            put("ONLINE_MODE", config.onlineMode.toString())
            config.noShowTimeoutSeconds?.let { put("YABRANKED_NO_SHOW_TIMEOUT_SECONDS", it.toString()) }
            config.postgameSeconds?.let { put("YABRANKED_POSTGAME_SECONDS", it.toString()) }
        } + mapOf(
            "YABRANKED_WORLD_SEED" to match.settings.worldSeed.toString(),
            "YABRANKED_BACKEND_URL" to config.backendUrlForAgents,
            "YABRANKED_MATCH_ID" to match.id.toString(),
            "YABRANKED_SERVER_TOKEN" to match.serverToken,
            "YABRANKED_CARD_SEED" to match.settings.cardSeed.toString(),
            "YABRANKED_TIME_LIMIT_MINUTES" to (match.settings.timeLimitSeconds / 60).toString(),
            "YABRANKED_PLAYER_A_UUID" to playerA.uuid.toString(),
            "YABRANKED_PLAYER_A_NAME" to playerA.name,
            "YABRANKED_PLAYER_B_UUID" to playerB.uuid.toString(),
            "YABRANKED_PLAYER_B_NAME" to playerB.name,
        )

        try {
            runtime.run(
                name = name,
                image = config.image,
                env = env,
                hostNetwork = config.hostNetwork,
                publishPorts = if (config.hostNetwork) emptyMap() else mapOf(port to containerPort),
            )
            containers[match.id.toString()] = name
            matchService.setServerAddress(match.id, "${config.publicHost}:$port")
            log.info("provisioned match {} on port {}", match.id, port)
        } catch (e: Exception) {
            log.error("failed to provision match ${match.id}; voiding", e)
            matchService.voidMatch(match.id)
            return
        }

        // reap servers that never report ready (crashed boot, bad image, ...)
        scope.launch(Dispatchers.IO) {
            delay(config.readyTimeout)
            val current = matches.get(match.id)
            if (current != null && current.status == MatchStatus.PENDING) {
                log.warn("match {} never became ready; voiding and reaping", match.id)
                matchService.voidMatch(match.id)
            }
        }
    }

    private fun teardown(matchId: String) {
        val name = containers.remove(matchId) ?: return
        log.info("tearing down container {}", name)
        runtime.remove(name)
    }
}
