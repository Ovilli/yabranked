package dev.yabranked.backend.ops

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadinessChecksTest {

    @Test
    fun `a backend whose matchmaking died is not ready`() = runTest {
        // the failure this exists for: every endpoint answers 200 while the
        // instance quietly matches nobody
        val checks = ReadinessChecks(matchmakingRunning = { false })

        assertEquals(listOf("matchmaking"), checks.failures())
    }

    @Test
    fun `a healthy backend on in-memory stores is ready`() = runTest {
        val checks = ReadinessChecks(matchmakingRunning = { true })

        assertTrue(checks.failures().isEmpty())
    }

    @Test
    fun `an unreachable database fails readiness`() = runTest {
        val checks = ReadinessChecks(
            matchmakingRunning = { true },
            databaseReachable = { false },
        )

        assertEquals(listOf("database"), checks.failures())
    }

    @Test
    fun `draining reports not ready so traffic moves away before the drop`() = runTest {
        val checks = ReadinessChecks(matchmakingRunning = { true }, draining = { true })

        assertEquals(listOf("draining"), checks.failures())
    }

    @Test
    fun `every failing check is reported, not just the first`() = runTest {
        val checks = ReadinessChecks(
            matchmakingRunning = { false },
            draining = { true },
            databaseReachable = { false },
        )

        assertEquals(listOf("draining", "matchmaking", "database"), checks.failures())
    }
}

class GracefulShutdownTest {

    @Test
    fun `steps run in registration order`() {
        val shutdown = GracefulShutdown()
        val order = mutableListOf<String>()
        shutdown.step("first") { order += "first" }
        shutdown.step("second") { order += "second" }
        shutdown.step("third") { order += "third" }

        shutdown.drain()

        assertEquals(listOf("first", "second", "third"), order)
    }

    @Test
    fun `a step that throws does not skip the ones after it`() {
        // the pool must still be closed even if telling queued clients failed
        val shutdown = GracefulShutdown()
        val ran = mutableListOf<String>()
        shutdown.step("boom") { error("simulated failure") }
        shutdown.step("cleanup") { ran += "cleanup" }

        shutdown.drain()

        assertEquals(listOf("cleanup"), ran)
    }

    @Test
    fun `draining twice runs the steps once`() {
        // the JVM hook and an explicit drain both fire on a normal stop
        val shutdown = GracefulShutdown()
        var runs = 0
        shutdown.step("once") { runs++ }

        shutdown.drain()
        shutdown.drain()

        assertEquals(1, runs)
    }

    @Test
    fun `isDraining flips as soon as the drain starts`() {
        val shutdown = GracefulShutdown()
        var seenDuringStep: Boolean? = null
        shutdown.step("observe") { seenDuringStep = shutdown.isDraining }

        assertTrue(!shutdown.isDraining)
        shutdown.drain()

        assertEquals(true, seenDuringStep, "readiness must already be red while steps run")
    }
}
