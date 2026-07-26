package dev.yabranked.backend.security

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Outcome of one [RateLimiter.acquire]; [retryAfterSeconds] is 0 when allowed. */
data class RateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long)

/**
 * Per-key token bucket: [limit] calls per [window], refilled continuously so a
 * caller that waits half a window gets half its budget back instead of nothing
 * until the window flips.
 *
 * Buckets are held in memory and swept once they have refilled completely —
 * otherwise an attacker cycling source addresses would grow the map without
 * bound, which is the same failure this limiter exists to prevent elsewhere.
 * Time comes from [nanoTime] (monotonic) so a clock step cannot hand out free
 * budget, and so tests can drive it.
 */
class RateLimiter(
    private val limit: Int,
    private val window: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(limit > 0) { "limit must be positive" }
    }

    private class Bucket(var tokens: Double, var updatedAt: Long)

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val windowNanos = window.inWholeNanoseconds
    private val lastSweep = AtomicLong(Long.MIN_VALUE)

    /** Spend one unit of [key]'s budget. */
    fun acquire(key: String): RateLimitDecision {
        val now = nanoTime()
        sweepIfDue(now)
        val bucket = buckets.computeIfAbsent(key) { Bucket(limit.toDouble(), now) }
        synchronized(bucket) {
            bucket.tokens = (bucket.tokens + refill(now - bucket.updatedAt)).coerceAtMost(limit.toDouble())
            bucket.updatedAt = now
            if (bucket.tokens < 1.0) {
                // seconds until one whole token is back, rounded up so a client
                // that obeys Retry-After is never turned away twice
                val missing = 1.0 - bucket.tokens
                val seconds = ceil(missing * window.inWholeSeconds / limit).toLong()
                return RateLimitDecision(allowed = false, retryAfterSeconds = maxOf(1L, seconds))
            }
            bucket.tokens -= 1.0
        }
        return RateLimitDecision(allowed = true, retryAfterSeconds = 0)
    }

    private fun refill(elapsedNanos: Long): Double =
        if (elapsedNanos <= 0) 0.0 else limit.toDouble() * elapsedNanos / windowNanos

    /**
     * Drop keys whose bucket is full again: they are indistinguishable from a
     * key that was never seen, so keeping them only costs memory.
     */
    private fun sweepIfDue(now: Long) {
        val previous = lastSweep.get()
        if (previous != Long.MIN_VALUE && now - previous < windowNanos) return
        if (!lastSweep.compareAndSet(previous, now)) return
        buckets.entries.removeIf { (_, bucket) ->
            synchronized(bucket) { bucket.tokens + refill(now - bucket.updatedAt) >= limit }
        }
    }

    /** Buckets currently tracked; exposed so tests can assert the sweep runs. */
    val trackedKeys: Int get() = buckets.size
}

/**
 * The budgets the API enforces. Every endpoint here gives an attacker something
 * for free without one: session minting proxies straight to Mojang's hasJoined
 * and grows the token registry, and the internal endpoints answer whether a
 * guessed per-match server token was right.
 */
class RateLimiters(
    /** Session minting, per source address. */
    val session: RateLimiter = RateLimiter(limit = 20, window = 1.minutes),
    /** Session minting, per requested username — one account, one budget. */
    val sessionIdentity: RateLimiter = RateLimiter(limit = 10, window = 1.minutes),
    /** Agent endpoints, per source address; caps server-token guessing. */
    val internal: RateLimiter = RateLimiter(limit = 60, window = 1.minutes),
)
