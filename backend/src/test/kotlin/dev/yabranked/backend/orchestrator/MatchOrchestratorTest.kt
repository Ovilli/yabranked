package dev.yabranked.backend.orchestrator

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.EloRatingSystem
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

private class FakeRuntime : ContainerRuntime {
    val started = ConcurrentLinkedQueue<Triple<String, String, Map<String, String>>>()
    val removed = ConcurrentLinkedQueue<String>()
    var failNext = false

    override fun run(
        name: String,
        image: String,
        env: Map<String, String>,
        hostNetwork: Boolean,
        publishPorts: Map<Int, Int>,
    ): String {
        if (failNext) error("simulated docker failure")
        started.add(Triple(name, image, env))
        return "container-$name"
    }

    override fun remove(name: String) {
        removed.add(name)
    }
}

class MatchOrchestratorTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val matchService = MatchService(players, matches, EloRatingSystem())
    private val runtime = FakeRuntime()
    private val orchestrator = MatchOrchestrator(
        config = OrchestratorConfig(
            image = "yabranked-match:test",
            publicHost = "match.example.com",
            backendUrlForAgents = "http://localhost:8080",
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
            assertEquals("yabranked-${match.id}", name)
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
            assertEquals("yabranked-${match.id}", runtime.removed.first())
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
}
