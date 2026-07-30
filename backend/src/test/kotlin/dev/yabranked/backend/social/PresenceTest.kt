package dev.yabranked.backend.social

import dev.yabranked.proto.PresenceState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PresenceTest {

    private val ttl: Duration = Duration.ofSeconds(90)
    private var now: Instant = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
    }
    private val presence = Presence(clock, ttl)

    @Test
    fun `an expired entry nobody asks about is still released`() {
        // Presence only shrinks when something looks at it: stateOf expires the
        // one player it was asked about, and the bulk sweep hung off a counter
        // nothing in the running server reads. A player who queued once and then
        // never appeared in a friends list or a party was never asked about
        // again either, so their entry stayed for the life of the process.
        val ghost = UUID.randomUUID()
        presence.set(ghost, PresenceState.QUEUE)

        now = now.plus(ttl).plusSeconds(1)
        presence.set(UUID.randomUUID(), PresenceState.MENUS)

        assertEquals(1, presence.onlineCount, "the expired entry was never released")
    }

    @Test
    fun `a live entry survives the sweep`() {
        val player = UUID.randomUUID()
        presence.set(player, PresenceState.QUEUE)

        now = now.plus(ttl).plusSeconds(1)
        presence.touch(player)
        presence.set(UUID.randomUUID(), PresenceState.MENUS)

        assertEquals(2, presence.onlineCount)
        assertEquals(PresenceState.QUEUE, presence.stateOf(player))
    }
}
