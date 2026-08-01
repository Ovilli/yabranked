package dev.yabranked.backend.orchestrator

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds

/**
 * Keeping a match container's log past its container.
 *
 * `docker rm -f` destroys the only copy of the one thing that can explain a
 * match-level bug, and the order matters as much as the copying: a log read
 * after the remove is no log at all.
 */
class MatchLogKeepingTest {

    /** Records the order of log-then-remove, which is the whole point. */
    private class LoggingRuntime : ContainerRuntime {
        val calls = ConcurrentLinkedQueue<String>()
        val started = ConcurrentLinkedQueue<String>()
        val removed = ConcurrentLinkedQueue<String>()

        override fun run(
            name: String,
            image: String,
            env: Map<String, String>,
            hostNetwork: Boolean,
            publishPorts: Map<Int, Int>,
            secretEnv: Map<String, String>,
            limits: ContainerLimits,
        ): String {
            started.add(name)
            return "container-$name"
        }

        override fun remove(name: String) {
            calls.add("remove:$name")
            removed.add(name)
        }

        override fun logs(name: String, tailLines: Int): String? {
            calls.add("logs:$name")
            return if (name in removed) null else "[yabranked] game started\n[yabranked] reporting result"
        }

        override fun list(namePrefix: String): List<String> =
            started.filter { it.startsWith(namePrefix) && it !in removed }

        override fun isRunning(name: String): Boolean = name in started && name !in removed
    }

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
    private val runtime = LoggingRuntime()
    private lateinit var logDir: Path

    private fun orchestrator(keep: Int = 50) = MatchOrchestrator(
        config = OrchestratorConfig(
            image = "yabranked-match:test",
            publicHost = "match.example.com",
            backendUrlForAgents = "http://localhost:8080",
            settleGrace = 100.milliseconds,
            logDir = logDir,
            logsKept = keep,
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

    private fun playAMatch(scope: CoroutineScope): UUID {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        matchService.getOrCreatePlayer(a, "A")
        matchService.getOrCreatePlayer(b, "B")
        val match = matchService.createMatch(
            QueueMatch(
                QueueEntry(a, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(b, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )
        awaitTrue { runtime.started.isNotEmpty() }
        matchService.settle(
            MatchResultReport(
                matchId = match.id.toString(),
                outcome = MatchOutcome.TEAM_A_WIN,
                durationSeconds = 60,
                teamAScore = 13,
                teamBScore = 7,
            ),
            match.serverToken,
        )
        return match.id
    }

    @Test
    fun `the log is kept before the container is removed`() {
        logDir = Files.createTempDirectory("yabranked-match-logs")
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            orchestrator().start(scope)
            val id = playAMatch(scope)
            awaitTrue { runtime.removed.isNotEmpty() }

            val kept = logDir.resolve("$id.log")
            assertTrue(Files.exists(kept), "no log was kept for $id")
            assertTrue(kept.readText().contains("game started"), "the kept log is not the container's output")
            // Reading after the remove would return nothing, which is exactly
            // the bug this ordering exists to prevent.
            assertEquals(
                listOf("logs", "remove"),
                runtime.calls.map { it.substringBefore(':') },
                "the log must be read before the container is destroyed",
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `nothing is written when no directory is configured`() {
        logDir = Files.createTempDirectory("yabranked-match-logs-off")
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            val off = MatchOrchestrator(
                config = OrchestratorConfig(
                    image = "yabranked-match:test",
                    publicHost = "match.example.com",
                    backendUrlForAgents = "http://localhost:8080",
                    settleGrace = 100.milliseconds,
                ),
                runtime = runtime,
                matchService = matchService,
                matches = matches,
                players = players,
            )
            off.start(scope)
            playAMatch(scope)
            awaitTrue { runtime.removed.isNotEmpty() }
            assertEquals(emptyList(), Files.list(logDir).use { it.toList() }, "wrote logs without a directory")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `only the newest logs are kept`() {
        logDir = Files.createTempDirectory("yabranked-match-logs-prune")
        val scope = CoroutineScope(Dispatchers.IO)
        try {
            orchestrator(keep = 2).start(scope)
            repeat(4) {
                playAMatch(scope)
                // Distinct mtimes, so "newest" is decidable on a coarse clock.
                Thread.sleep(1100)
            }
            awaitTrue { runtime.removed.size == 4 }
            awaitTrue {
                Files.list(logDir).use { files -> files.toList().size } <= 2
            }
        } finally {
            scope.cancel()
        }
    }
}
