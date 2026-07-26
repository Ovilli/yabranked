package dev.yabranked.backend.orchestrator

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.ops.OrchestratorMetrics
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
import kotlin.time.Duration.Companion.seconds

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
    /**
     * Resource ceiling for each match container. One runaway match server
     * otherwise starves every other match sharing the host.
     */
    val limits: ContainerLimits = ContainerLimits(),
    /** Optional override for the agent's no-show timeout (seconds). */
    val noShowTimeoutSeconds: Long? = null,
    /** Optional override for how long the server lingers after a match (seconds). */
    val postgameSeconds: Long? = null,
    /**
     * How long a match may stay PENDING before it is voided and reaped. Kept
     * just past the client's own wait (`MatchService.PROVISION_TIMEOUT_SECONDS`)
     * so a client that gives up doesn't leave the container running.
     */
    val readyTimeout: Duration =
        (MatchService.PROVISION_TIMEOUT_SECONDS + MatchService.PROVISION_REAP_GRACE_SECONDS).seconds,
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
    private val metrics: OrchestratorMetrics = OrchestratorMetrics.NONE,
) {
    private val log = LoggerFactory.getLogger("orchestrator")
    private val containers = ConcurrentHashMap<String, String>() // matchId -> container name

    /** Ports currently handed out to live matches; a port is free once released. */
    private val portsInUse = ConcurrentHashMap.newKeySet<Int>()
    private val matchPorts = ConcurrentHashMap<String, Int>() // matchId -> port

    fun start(scope: CoroutineScope) {
        matchService.onMatchCreated { match ->
            scope.launch(Dispatchers.IO) { provision(scope, match) }
        }
        matchService.onMatchSettled { match ->
            scope.launch(Dispatchers.IO) { teardown(match.id.toString()) }
        }
    }

    /**
     * Clears matches orphaned by a restart. The previous process owned the
     * containers and the timers watching them; both died with it, so anything
     * still PENDING or ACTIVE will never be settled and its container would
     * linger forever. Voiding releases the players; the sweep kills the
     * containers, including any whose match row is already gone.
     */
    fun reconcile() {
        val orphans = matches.unsettled()
        for (match in orphans) {
            log.warn("voiding match {} orphaned by a restart (status {})", match.id, match.status)
            runCatching { matchService.voidMatch(match.id) }
                .onFailure { log.error("could not void orphaned match ${match.id}", it) }
        }
        val leftovers = runCatching { runtime.list(CONTAINER_PREFIX) }
            .onFailure { log.error("could not list match containers", it) }
            .getOrDefault(emptyList())
        for (name in leftovers) {
            log.warn("removing leftover match container {}", name)
            runtime.remove(name)
        }
        if (orphans.isNotEmpty() || leftovers.isNotEmpty()) {
            log.info("reconciled {} orphaned matches, {} leftover containers", orphans.size, leftovers.size)
        }
    }

    /**
     * Claims a free port in the configured range, or null when the range is
     * exhausted. The old modulo counter reused live ports once it wrapped,
     * which in host-network mode produced a container that started fine and
     * then silently failed to bind.
     */
    private fun claimPort(matchId: String): Int? {
        for (offset in 0 until config.portRangeSize) {
            val port = config.portRangeStart + offset
            if (portsInUse.add(port)) {
                matchPorts[matchId] = port
                return port
            }
        }
        return null
    }

    private fun releasePort(matchId: String) {
        matchPorts.remove(matchId)?.let(portsInUse::remove)
    }

    private fun provision(scope: CoroutineScope, match: MatchRecord) {
        val name = "$CONTAINER_PREFIX${match.id}"
        val port = claimPort(match.id.toString())
        if (port == null) {
            log.error("no free port in {}..{}; voiding match {}",
                config.portRangeStart, config.portRangeStart + config.portRangeSize - 1, match.id)
            metrics.provisionFailed("no_port")
            matchService.voidMatch(match.id)
            return
        }
        val playerA = players.getPlayer(match.playerA)
        val playerB = players.getPlayer(match.playerB)
        if (playerA == null || playerB == null) {
            // used to throw straight out of the launched coroutine, leaving the
            // match PENDING forever with nothing scheduled to reap it
            log.error("match {} references an unknown player; voiding", match.id)
            metrics.provisionFailed("unknown_player")
            releasePort(match.id.toString())
            matchService.voidMatch(match.id)
            return
        }

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
            "YABRANKED_CARD_SEED" to match.settings.cardSeed.toString(),
            "YABRANKED_TIME_LIMIT_MINUTES" to (match.settings.timeLimitSeconds / 60).toString(),
            "YABRANKED_RULES" to kotlinx.serialization.json.Json.encodeToString(
                dev.yabranked.proto.MatchRules.serializer(), match.format.rules,
            ),
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
                // the one value that must not show up in the host's process list
                secretEnv = mapOf("YABRANKED_SERVER_TOKEN" to match.serverToken),
                limits = config.limits,
            )
            containers[match.id.toString()] = name
            matchService.setServerAddress(match.id, "${config.publicHost}:$port")
            metrics.provisioned()
            log.info("provisioned match {} on port {}", match.id, port)
        } catch (e: Exception) {
            log.error("failed to provision match {}; voiding", match.id, e)
            metrics.provisionFailed("run_failed")
            releasePort(match.id.toString())
            matchService.voidMatch(match.id)
            return
        }

        // reap servers that never report ready (crashed boot, bad image, ...)
        scope.launch(Dispatchers.IO) {
            delay(config.readyTimeout)
            val current = matches.get(match.id)
            if (current != null && current.status == MatchStatus.PENDING) {
                log.warn("match {} never became ready; voiding and reaping", match.id)
                metrics.provisionTimedOut()
                matchService.voidMatch(match.id)
            }
        }
    }

    private fun teardown(matchId: String) {
        releasePort(matchId)
        val name = containers.remove(matchId) ?: return
        log.info("tearing down container {}", name)
        runtime.remove(name)
    }

    companion object {
        /** Name prefix for every match container; also how the sweep finds them. */
        const val CONTAINER_PREFIX = "yabranked-"
    }
}
