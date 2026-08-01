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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The agent's own default postgame linger (`AgentConfig.postgameSeconds`),
 * mirrored here so the teardown grace covers it when nothing overrides it.
 */
private const val DEFAULT_POSTGAME_SECONDS = 20L

data class OrchestratorConfig(
    /** Docker image for the match server (see docker/ in this repo). */
    val image: String,
    /** Host players connect to, e.g. the server's public IP or "localhost". */
    val publicHost: String,
    /** Backend URL reachable from inside the container (host network ⇒ localhost works). */
    val backendUrlForAgents: String,
    val portRangeStart: Int = 25600,
    val portRangeSize: Int = 50,
    /**
     * Local port -> the address a client should actually dial, for hosts whose
     * ports are not reachable under their own numbers.
     *
     * A tunnel (playit.gg, and anything else that avoids port forwarding) hands
     * out a public host and port of *its* choosing and forwards them to a local
     * port you pick. The two numbers rarely match, and `publicHost:localPort`
     * then sends every player to a port nobody is listening on.
     *
     * When this is set it also *replaces* the port range: only mapped ports are
     * allocatable, because an unmapped one is a match nobody can reach. That
     * makes the size of the map the real cap on concurrent matches.
     */
    val portMap: Map<Int, String> = emptyMap(),
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
     * How long a *decided* match's container is left alive after it settles.
     *
     * `docker rm -f` the instant the result lands is a SIGKILL on a container
     * that is still working. Two things were lost to it, both of them things
     * the agent is documented to do:
     *
     * - **The replay.** A settle the agent did not initiate — the player-facing
     *   `POST /v1/matches/{id}/forfeit`, which the forfeit screen fires
     *   alongside the in-game `/forfeit` and usually wins the race to the
     *   backend — killed the container before it ever uploaded, so every
     *   "Save replay" on such a match answered "no replay was recorded".
     * - **The postgame linger.** The agent keeps the server up for
     *   [postgameSeconds] so players can read YAB's game-over screen; the
     *   container was destroyed under them long before that elapsed.
     *
     * Voided matches are still torn down immediately: nobody is reading a
     * game-over screen for a match that never happened, and a never-ready
     * container holding its port for three minutes is the bug the reaper
     * exists to prevent.
     */
    val settleGrace: Duration = ((postgameSeconds ?: DEFAULT_POSTGAME_SECONDS) + 15).seconds,
    /**
     * How long a match may stay PENDING before it is voided and reaped. Kept
     * just past the client's own wait (`MatchService.PROVISION_TIMEOUT_SECONDS`)
     * so a client that gives up doesn't leave the container running.
     */
    val readyTimeout: Duration =
        (MatchService.PROVISION_TIMEOUT_SECONDS + MatchService.PROVISION_REAP_GRACE_SECONDS).seconds,
    /**
     * How often to check that each live match's container is still running.
     *
     * A match server can die *after* it went ACTIVE — a crash, an OOM kill, the
     * vanilla watchdog deciding a long worldgen tick is a hang. Nothing else
     * notices: the ready-timeout reaper only ever looks at PENDING matches, so
     * the row stayed ACTIVE forever, its port was never released, and both
     * players were left holding a match they could neither finish nor leave.
     */
    val livenessInterval: Duration = 30.seconds,
    /**
     * Directory each finished match's container log is copied into, or null to
     * keep discarding them.
     *
     * Teardown is `docker rm -f`, which destroys the only copy of the one thing
     * that can explain a match-level bug. "Why did the result never arrive" is
     * not answerable from the backend's own log — the backend sees a match that
     * simply stopped talking — and the container that could have answered it was
     * deleted seconds after the question arose. Written on the way out rather
     * than streamed, because a match server that is still running still has its
     * log where it belongs.
     */
    val logDir: java.nio.file.Path? = null,
    /** Lines kept from the tail of a container log. A match server is chatty. */
    val logTailLines: Int = 5_000,
    /**
     * Kept container logs, newest first; older ones are deleted as new ones
     * arrive. Retention is a count rather than an age because the thing being
     * rationed is disk on a small host, and matches arrive irregularly.
     */
    val logsKept: Int = 50,
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
            scope.launch(Dispatchers.IO) {
                // A decided match keeps its container for the grace period: the
                // agent may still be uploading the recording (a forfeit settled
                // over the player's own token gets here first) and the players
                // are still on the game-over screen. See [settleGrace].
                if (match.outcome != dev.yabranked.proto.MatchOutcome.VOID) delay(config.settleGrace)
                teardown(match.id.toString())
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(config.livenessInterval)
                runCatching { sweepDeadContainers() }
                    .onFailure { log.error("liveness sweep failed", it) }
            }
        }
    }

    /**
     * Void every match whose container is gone.
     *
     * The agent reports the result as the game ends, so a container that dies
     * first takes the result with it. Voiding is the honest outcome: nobody
     * finished, and neither player's rating should move for a match the
     * infrastructure lost. Doing nothing — which is what used to happen — left
     * the row ACTIVE forever, and with it a port that was never released and two
     * players the queue would not take back.
     */
    private fun sweepDeadContainers() {
        // Snapshot: voiding fires the settle listeners, which mutate `containers`.
        for ((matchId, name) in containers.entries.map { it.key to it.value }) {
            if (runtime.isRunning(name)) continue
            val id = runCatching { UUID.fromString(matchId) }.getOrNull() ?: continue
            val current = matches.get(id) ?: continue
            if (current.status != MatchStatus.PENDING && current.status != MatchStatus.ACTIVE) continue
            log.warn("container {} for match {} is gone; voiding the match", name, matchId)
            metrics.serverDied()
            matchService.voidMatch(id)
            // voidMatch settles, and the settle listener tears the container
            // down — but only if it is still registered, so make sure the port
            // comes back either way.
            teardown(matchId)
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
        // Only containers this orchestrator could have created. The name has to
        // carry a match id, not merely start with the prefix: this list is fed
        // straight into `remove`, so anything it wrongly matches is destroyed.
        // It once ate a hand-run `yabranked-postgres` — the backend deleting its
        // own database, twice, before the pattern was recognised.
        val leftovers = runCatching { runtime.list(CONTAINER_PREFIX) }
            .onFailure { log.error("could not list match containers", it) }
            .getOrDefault(emptyList())
            .filter { name -> looksLikeMatchContainer(name) }
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
        // A mapped setup can only use ports the tunnel actually forwards.
        val candidates =
            if (config.portMap.isNotEmpty()) config.portMap.keys.sorted()
            else (0 until config.portRangeSize).map { config.portRangeStart + it }
        for (port in candidates) {
            if (portsInUse.add(port)) {
                matchPorts[matchId] = port
                return port
            }
        }
        return null
    }

    /** What a client is told to connect to for a match on [port]. */
    private fun addressFor(port: Int): String =
        config.portMap[port] ?: "${config.publicHost}:$port"

    /** The side-ordered roster as the agent's `YABRANKED_TEAMS` expects it. */
    private fun rosterJson(
        match: MatchRecord,
        roster: Map<java.util.UUID, dev.yabranked.backend.store.PlayerRecord>,
    ): String = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(
            kotlinx.serialization.builtins.ListSerializer(RosterEntry.serializer())
        ),
        match.rosters.map { side ->
            side.map { uuid -> RosterEntry(uuid.toString(), roster[uuid]?.name ?: "?") }
        },
    )

    @kotlinx.serialization.Serializable
    private data class RosterEntry(val uuid: String, val name: String)

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
        // Every participant, not just the two captains: a 3v3 whose other four
        // accounts have vanished must not be provisioned either.
        val roster = players.getPlayers(match.participants)
        if (playerA == null || playerB == null || roster.size != match.participants.size) {
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
            // The full side-ordered roster, which is what gates joins and picks
            // YAB teams once a side can hold more than one player. The two
            // PLAYER_A/B variables stay for agents that predate it.
            "YABRANKED_TEAMS" to rosterJson(match, roster),
            "YABRANKED_SHARED_WORLD" to match.settings.sharedWorld.toString(),
            "YABRANKED_SHARED_SEED" to match.settings.sharedSeed.toString(),
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
            matchService.setServerAddress(match.id, addressFor(port))
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
        // Before the remove, never after: `docker rm -f` takes the log with it.
        // Failing to keep a log must not stop the teardown — a container left
        // running holds a port, and that costs an actual match.
        runCatching { keepLog(matchId, name) }
            .onFailure { log.warn("could not keep the log of {}: {}", name, it.toString()) }
        runtime.remove(name)
    }

    /**
     * Copy [name]'s console output into [OrchestratorConfig.logDir].
     *
     * Named after the match rather than the container so it can be found from a
     * match id, which is what a player report carries.
     */
    private fun keepLog(matchId: String, name: String) {
        val dir = config.logDir ?: return
        val text = runtime.logs(name, config.logTailLines) ?: return
        java.nio.file.Files.createDirectories(dir)
        val file = dir.resolve("$matchId.log")
        java.nio.file.Files.writeString(file, text)
        log.info("kept {} lines of {} in {}", text.lineSequence().count(), name, file)
        pruneLogs(dir)
    }

    /** Keep the newest [OrchestratorConfig.logsKept] files, delete the rest. */
    private fun pruneLogs(dir: java.nio.file.Path) {
        val files = java.nio.file.Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".log") }.toList()
        }
        if (files.size <= config.logsKept) return
        files.sortedByDescending { runCatching { java.nio.file.Files.getLastModifiedTime(it) }.getOrNull() }
            .drop(config.logsKept)
            .forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } }
    }

    /**
     * Whether [name] is one of ours to delete.
     *
     * The suffix must parse as a UUID. A prefix alone is not enough of a claim
     * when the consequence of a false positive is `docker rm -f` on somebody
     * else's container.
     */
    private fun looksLikeMatchContainer(name: String): Boolean =
        name.startsWith(CONTAINER_PREFIX) &&
            runCatching { java.util.UUID.fromString(name.removePrefix(CONTAINER_PREFIX)) }.isSuccess

    companion object {
        /**
         * Name prefix for every match container; also how the sweep finds them.
         *
         * Specific on purpose. It used to be `yabranked-`, which matched anything
         * else named for this project on the same host — a database container
         * included — and the sweep deletes what it matches.
         */
        const val CONTAINER_PREFIX = "yabranked-match-"
    }
}
