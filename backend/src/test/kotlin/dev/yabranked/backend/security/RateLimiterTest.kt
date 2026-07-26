package dev.yabranked.backend.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class RateLimiterTest {

    /** Drives the limiter's monotonic clock so the tests never sleep. */
    private class FakeClock(var nanos: Long = 0L) : () -> Long {
        override fun invoke(): Long = nanos
        fun advanceSeconds(seconds: Long) {
            nanos += seconds * 1_000_000_000L
        }
    }

    @Test
    fun `a key spends its budget and is then refused`() {
        val limiter = RateLimiter(limit = 3, window = 1.minutes, nanoTime = FakeClock())

        repeat(3) { assertTrue(limiter.acquire("a").allowed, "call ${it + 1} should be allowed") }

        assertTrue(!limiter.acquire("a").allowed, "the fourth call is over budget")
    }

    @Test
    fun `keys have separate budgets`() {
        // one noisy address must not lock out everyone else
        val limiter = RateLimiter(limit = 1, window = 1.minutes, nanoTime = FakeClock())
        limiter.acquire("a")

        assertTrue(!limiter.acquire("a").allowed)
        assertTrue(limiter.acquire("b").allowed)
    }

    @Test
    fun `budget refills continuously rather than at a window boundary`() {
        val clock = FakeClock()
        val limiter = RateLimiter(limit = 60, window = 1.minutes, nanoTime = clock)
        repeat(60) { limiter.acquire("a") }
        assertTrue(!limiter.acquire("a").allowed)

        // a third of a window back should be worth roughly a third of the budget
        clock.advanceSeconds(20)

        repeat(19) { assertTrue(limiter.acquire("a").allowed, "refill ${it + 1} should be allowed") }
    }

    @Test
    fun `retryAfter is at least a second and enough to actually succeed`() {
        val clock = FakeClock()
        val limiter = RateLimiter(limit = 60, window = 1.minutes, nanoTime = clock)
        repeat(60) { limiter.acquire("a") }

        val refused = limiter.acquire("a")
        assertTrue(!refused.allowed)
        assertTrue(refused.retryAfterSeconds >= 1, "a client told to retry in 0s would spin")

        // obeying Retry-After must not be refused a second time
        clock.advanceSeconds(refused.retryAfterSeconds)
        assertTrue(limiter.acquire("a").allowed, "Retry-After was too short")
    }

    @Test
    fun `fully refilled keys are swept so cycling addresses cannot grow the map`() {
        val clock = FakeClock()
        val limiter = RateLimiter(limit = 2, window = 1.minutes, nanoTime = clock)
        repeat(50) { limiter.acquire("addr-$it") }
        assertEquals(50, limiter.trackedKeys)

        // every bucket is full again; the sweep runs on the next acquire
        clock.advanceSeconds(120)
        limiter.acquire("trigger")

        assertTrue(
            limiter.trackedKeys < 50,
            "abandoned buckets were kept: ${limiter.trackedKeys} still tracked",
        )
    }

    @Test
    fun `a nonpositive limit is rejected`() {
        assertFailsWith<IllegalArgumentException> { RateLimiter(limit = 0, window = 1.minutes) }
    }
}
