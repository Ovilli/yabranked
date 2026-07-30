package dev.yabranked.backend.orchestrator

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

private class FakeRuntime : ContainerRuntime {
    val started = ConcurrentLinkedQueue<Triple<String, String, Map<String, String>>>()
    val removed = ConcurrentLinkedQueue<String>()
    var failNext = false

    /** Limits the last [run] was asked for; the host protection is easy to drop silently. */
    var lastLimits: ContainerLimits? = null

    override fun run(
        name: String,
        image: String,
        env: Map<String, String>,
        hostNetwork: Boolean,
        publishPorts: Map<Int, Int>,
        secretEnv: Map<String, String>,
        limits: ContainerLimits,
    ): String {
        lastLimits = limits
        if (failNext) error("simulated docker failure")
        // the container sees both maps as one environment; how they got there
        // is the runtime's business, not the orchestrator's
        started.add(Triple(name, image, env + secretEnv))
        return "container-$name"
    }

    override fun remove(name: String) {
        removed.add(name)
    }

    /** Containers still "running": started, minus the ones already removed. */
    override fun list(namePrefix: String): List<String> =
        started.map { it.first }.filter { it.startsWith(namePrefix) && it !in removed }

    /** Containers the test has declared dead without going through [remove]. */
    val died = ConcurrentLinkedQueue<String>()

    override fun isRunning(name: String): Boolean =
        name !in died && name !in removed && started.any { it.first == name }
}

class MatchOrchestratorTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
    private val runtime = FakeRuntime()
    private val orchestrator = MatchOrchestrator(
        config = OrchestratorConfig(
            image = "yabranked-match:test",
            publicHost = "match.example.com",
            backendUrlForAgents = "http://localhost:8080",
            limits = ContainerLimits(memory = "4g", cpus = "2"),
            // Production waits out the postgame linger before reaping a decided
            // match; a test only needs the ordering, not the three minutes.
            settleGrace = 300.milliseconds,
        ),
        runtime = runtime,
        matchService = matchService,
        matches = matches,
        players = players,
    )

    private fun awaitTrue(timeoutMs: Long = 5000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("condition not met within ${timeoutMs}ms")
    }

    private fun createMatch(): dev.yabranked.backend.store.MatchRecord {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        matchService.getOrCreatePlayer(a, "A")
        matchService.getOrCreatePlayer(b, "B")
        return matchService.createMatch(
            QueueMatch(
                QueueEntry(a, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(b, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )
    }

    @Test
    fun `provisions container with match environment and records address`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            orchestrator.start(scope)
            val match = createMatch()

            awaitTrue { runtime.started.isNotEmpty() }
            val (name, image, env) = runtime.started.first()
            assertEquals("yabranked-match-${match.id}", name)
            assertEquals("yabranked-match:test", image)
            assertEquals(match.id.toString(), env["YABRANKED_MATCH_ID"])
            assertEquals(match.serverToken, env["YABRANKED_SERVER_TOKEN"])
            assertEquals(match.settings.cardSeed.toString(), env["YABRANKED_CARD_SEED"])

            awaitTrue { matches.get(match.id)?.serverAddress != null }
            val address = matches.get(match.id)!!.serverAddress!!
            assertTrue(address.startsWith("match.example.com:256"), "unexpected address $address")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `match containers are capped so one cannot starve the host`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            orchestrator.start(scope)
            createMatch()

            awaitTrue { runtime.started.isNotEmpty() }
            assertEquals(ContainerLimits(memory = "4g", cpus = "2"), runtime.lastLimits)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `tears down container when the match settles`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            orchestrator.start(scope)
            val match = createMatch()
            awaitTrue { runtime.started.isNotEmpty() }

            matchService.markReady(match.id.toString(), match.serverToken)
            matchService.settle(
                MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 5),
                match.serverToken,
            )

            awaitTrue { runtime.removed.isNotEmpty() }
            assertEquals("yabranked-match-${match.id}", runtime.removed.first())
        } finally {
            scope.cancel()
        }
    }

    /**
     * The container outlives the result by the grace period, because it is
     * still doing two things at that moment: uploading the match's replay, and
     * showing both players the game-over screen. Killing it the instant the
     * result landed lost the recording of every match settled from the client
     * side — which is what "no replay was recorded for that match" meant.
     */
    @Test
    fun `a decided match keeps its container for the postgame grace`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            orchestrator.start(scope)
            val match = createMatch()
            awaitTrue { runtime.started.isNotEmpty() }
            matchService.markReady(match.id.toString(), match.serverToken)

            // Settled by the player, not by the agent: exactly the case where
            // the container still has an upload to finish.
            matchService.forfeit(match.id, match.playerA)

            Thread.sleep(100)
            assertTrue(runtime.removed.isEmpty(), "the container was reaped before it could finish uploading")

            awaitTrue { runtime.removed.isNotEmpty() }
            assertEquals("yabranked-match-${match.id}", runtime.removed.first())
        } finally {
            scope.cancel()
        }
    }

    /** Nothing is worth waiting for on a match that never happened. */
    @Test
    fun `a voided match is torn down at once`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            orchestrator.start(scope)
            val match = createMatch()
            awaitTrue { runtime.started.isNotEmpty() }

            matchService.voidMatch(match.id)

            awaitTrue(timeoutMs = 250) { runtime.removed.isNotEmpty() }
            assertEquals("yabranked-match-${match.id}", runtime.removed.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reconcile voids matches orphaned by a restart and sweeps their containers`() {
        val match = createMatch() // no orchestrator running: nothing provisioned it
        val stale = "yabranked-match-${java.util.UUID.randomUUID()}"
        runtime.started.add(Triple(stale, "yabranked-match:test", emptyMap()))

        orchestrator.reconcile()

        assertEquals(MatchStatus.VOIDED, matches.get(match.id)?.status)
        assertTrue(stale in runtime.removed, "leftover container not swept")
    }

    @Test
    fun `reconcile leaves containers it did not create alone`() {
        // The sweep feeds this list straight into `docker rm -f`. When the prefix
        // was merely `yabranked-` it destroyed a database container named
        // `yabranked-postgres` on the same host — twice — and each time it looked
        // like Postgres had crashed rather than been deleted.
        val bystanders = listOf(
            "yabranked-postgres",
            "yabranked-minio",
            "yabranked-match-not-a-uuid",
        )
        bystanders.forEach { runtime.started.add(Triple(it, "someone-elses:image", emptyMap())) }

        orchestrator.reconcile()

        bystanders.forEach { assertTrue(it !in runtime.removed, "$it must not be swept") }
    }

    @Test
    fun `does not hand the same port to two live matches`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            orchestrator.start(scope)
            val first = createMatch()
            val second = createMatch()

            awaitTrue { runtime.started.size == 2 }
            awaitTrue {
                matches.get(first.id)?.serverAddress != null && matches.get(second.id)?.serverAddress != null
            }
            assertTrue(
                matches.get(first.id)!!.serverAddress != matches.get(second.id)!!.serverAddress,
                "both matches were given the same address",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `voids the match when the port range is exhausted`() {
        val scope = CoroutineScope(Dispatchers.Default)
        val tiny = MatchOrchestrator(
            config = OrchestratorConfig(
                image = "yabranked-match:test",
                publicHost = "match.example.com",
                backendUrlForAgents = "http://localhost:8080",
                portRangeSize = 1,
            ),
            runtime = runtime,
            matchService = matchService,
            matches = matches,
            players = players,
        )
        try {
            tiny.start(scope)
            createMatch()
            awaitTrue { runtime.started.isNotEmpty() }
            val second = createMatch()

            awaitTrue { matches.get(second.id)?.status == MatchStatus.VOIDED }
            assertEquals(1, runtime.started.size, "second match should not have started a container")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `voids the match if provisioning fails`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            orchestrator.start(scope)
            runtime.failNext = true
            val match = createMatch()

            awaitTrue { matches.get(match.id)?.status == MatchStatus.VOIDED }
        } finally {
            scope.cancel()
        }
    }

    /** An orchestrator that sweeps often enough for a test to watch it. */
    private fun brisk() = MatchOrchestrator(
        config = OrchestratorConfig(
            image = "yabranked-match:test",
            publicHost = "match.example.com",
            backendUrlForAgents = "http://localhost:8080",
            livenessInterval = 50.milliseconds,
        ),
        runtime = runtime,
        matchService = matchService,
        matches = matches,
        players = players,
    )

    @Test
    fun `voids a match whose container dies after it went live`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            brisk().start(scope)
            val match = createMatch()
            awaitTrue { runtime.started.isNotEmpty() }
            val name = runtime.started.first().first
            // The match is up and being played; then the server dies — a crash,
            // an OOM kill, or the vanilla watchdog calling a long worldgen tick
            // a hang. Nothing reports a result, because nothing is left to.
            matchService.markReady(match.id.toString(), match.serverToken)
            awaitTrue { matches.get(match.id)?.status == MatchStatus.ACTIVE }
            runtime.died.add(name)

            awaitTrue {
                matches.get(match.id)?.status == MatchStatus.VOIDED
            }
            assertTrue(name in runtime.removed, "the dead container was never cleaned up")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a live container is left alone by the sweep`() {
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            brisk().start(scope)
            val match = createMatch()
            awaitTrue { runtime.started.isNotEmpty() }
            matchService.markReady(match.id.toString(), match.serverToken)
            awaitTrue { matches.get(match.id)?.status == MatchStatus.ACTIVE }

            // Several sweeps' worth of a perfectly healthy match.
            Thread.sleep(300)

            assertEquals(
                MatchStatus.ACTIVE,
                matches.get(match.id)?.status,
                "the sweep voided a match that was still being played",
            )
            assertTrue(runtime.removed.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
