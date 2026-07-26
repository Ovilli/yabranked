package dev.yabranked.backend.queue

import dev.yabranked.proto.MatchFormat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Clock we can move forward manually to simulate queue wait time. */
private class MutableClock(private var now: Instant) : Clock() {
    override fun instant(): Instant = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this
    fun advance(duration: Duration) {
        now += duration
    }
}

class MatchmakingQueueTest {

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val queue = MatchmakingQueue(
        initialBand = 100,
        bandPerSecond = 5.0,
        maxBand = 1000,
        clock = clock,
    )

    private fun player() = UUID.randomUUID()

    @Test
    fun `players within initial band match immediately`() {
        val a = player()
        val b = player()
        queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(b, 1080, MatchFormat.LOCKOUT_1V1)

        val matches = queue.tick()
        assertEquals(1, matches.size)
        assertEquals(0, queue.size)
    }

    @Test
    fun `players outside band do not match until it expands`() {
        val a = player()
        val b = player()
        queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(b, 1300, MatchFormat.LOCKOUT_1V1)

        assertTrue(queue.tick().isEmpty(), "300 gap > 100 initial band")

        // 40s * 5/s + 100 = 300 band -> now covers the gap
        clock.advance(Duration.ofSeconds(40))
        val matches = queue.tick()
        assertEquals(1, matches.size)
    }

    @Test
    fun `both players bands must cover the gap`() {
        val a = player()
        val b = player()
        queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1)
        clock.advance(Duration.ofSeconds(100)) // a's band is wide now
        queue.enqueue(b, 1400, MatchFormat.LOCKOUT_1V1) // b just arrived: band 100

        assertTrue(queue.tick().isEmpty(), "b's band does not accept a 400 gap yet")
    }

    @Test
    fun `closest rating wins among candidates`() {
        val seeker = player()
        val close = player()
        val far = player()
        queue.enqueue(seeker, 1000, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(far, 1090, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(close, 1010, MatchFormat.LOCKOUT_1V1)

        val matches = queue.tick()
        assertEquals(1, matches.size)
        val matchedIds = setOf(matches[0].playerA.uuid, matches[0].playerB.uuid)
        assertTrue(seeker in matchedIds && close in matchedIds)
        assertTrue(queue.contains(far))
    }

    @Test
    fun `duplicate enqueue rejected`() {
        val a = player()
        assertTrue(queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1))
        assertFalse(queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1))
    }

    @Test
    fun `band expansion caps at maxBand`() {
        val a = player()
        val b = player()
        queue.enqueue(a, 0, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(b, 1500, MatchFormat.LOCKOUT_1V1)

        clock.advance(Duration.ofHours(1)) // bands hit the 1000 cap
        assertTrue(queue.tick().isEmpty(), "1500 gap > 1000 max band")
    }

    @Test
    fun `eta is zero when the initial band already covers the gap`() {
        val a = player()
        queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(player(), 1080, MatchFormat.LOCKOUT_1V1)

        assertEquals(0L, queue.etaSeconds(a))
    }

    @Test
    fun `eta counts the seconds of band expansion the gap still needs`() {
        val a = player()
        queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(player(), 1300, MatchFormat.LOCKOUT_1V1)

        // gap 300, initial band 100, 5/s -> 40s before either band reaches it
        assertEquals(40L, queue.etaSeconds(a))
        clock.advance(Duration.ofSeconds(25))
        assertEquals(15L, queue.etaSeconds(a))
    }

    @Test
    fun `eta is bound by the player who has waited less`() {
        val early = player()
        queue.enqueue(early, 1000, MatchFormat.LOCKOUT_1V1)
        clock.advance(Duration.ofSeconds(40))
        val late = player()
        queue.enqueue(late, 1300, MatchFormat.LOCKOUT_1V1)

        // early's band already covers the 300 gap; late still needs the full 40s
        assertEquals(40L, queue.etaSeconds(early))
        assertEquals(40L, queue.etaSeconds(late))
    }

    @Test
    fun `eta picks the soonest of several candidates`() {
        val a = player()
        queue.enqueue(a, 1000, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(player(), 1500, MatchFormat.LOCKOUT_1V1) // 80s
        queue.enqueue(player(), 1200, MatchFormat.LOCKOUT_1V1) // 20s

        assertEquals(20L, queue.etaSeconds(a))
    }

    @Test
    fun `eta is null when no opponent can ever be reached`() {
        val alone = player()
        queue.enqueue(alone, 1000, MatchFormat.LOCKOUT_1V1)
        assertEquals(null, queue.etaSeconds(alone), "nobody else queued")

        // beyond maxBand, and in another format — neither is ever pairable
        queue.enqueue(player(), 3000, MatchFormat.LOCKOUT_1V1)
        queue.enqueue(player(), 1000, MatchFormat.CASUAL_LOCKOUT)
        assertEquals(null, queue.etaSeconds(alone))
    }

    @Test
    fun `eta is null for a player who is not queued`() {
        assertEquals(null, queue.etaSeconds(player()))
    }

    @Test
    fun `four players produce two matches`() {
        repeat(4) { i ->
            queue.enqueue(player(), 1000 + i * 10, MatchFormat.LOCKOUT_1V1)
        }
        assertEquals(2, queue.tick().size)
        assertEquals(0, queue.size)
    }
}
