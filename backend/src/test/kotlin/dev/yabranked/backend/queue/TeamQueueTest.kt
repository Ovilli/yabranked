package dev.yabranked.backend.queue

import dev.yabranked.proto.MatchFormat
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Team formats and party tickets. The 1v1 behaviour is covered by
 * [MatchmakingQueueTest]; this is about the two rules that only matter once a
 * ticket can hold more than one player — a party is never split, and a match
 * only forms at exactly the right size.
 */
class TeamQueueTest {

    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val queue = MatchmakingQueue(clock = clock)

    private fun solo(rating: Int, format: MatchFormat = MatchFormat.RANKED_2V2): UUID =
        UUID.randomUUID().also { assertTrue(queue.enqueue(it, rating, format)) }

    private fun party(
        vararg ratings: Int,
        format: MatchFormat = MatchFormat.RANKED_2V2,
    ): Pair<UUID, List<UUID>> {
        val id = UUID.randomUUID()
        val members = ratings.map { UUID.randomUUID() to it }
        assertTrue(queue.enqueueParty(id, members, format))
        return id to members.map { it.first }
    }

    @Test
    fun `four solo players make one 2v2`() {
        val players = listOf(solo(1000), solo(1010), solo(990), solo(1005))

        val pairings = queue.tick()

        assertEquals(1, pairings.size)
        val pairing = pairings.single()
        assertEquals(MatchFormat.RANKED_2V2, pairing.format)
        assertEquals(2, pairing.sides.size)
        assertTrue(pairing.sides.all { it.size == 2 })
        assertEquals(players.toSet(), pairing.players.map { it.uuid }.toSet())
        assertFalse(pairing.isSolo)
        assertEquals(0, queue.size)
    }

    @Test
    fun `three players do not start a 2v2`() {
        solo(1000); solo(1000); solo(1000)

        assertTrue(queue.tick().isEmpty(), "a team match must not start a player short")
        assertEquals(3, queue.size)
    }

    @Test
    fun `auto-filled sides are balanced by rating, not by arrival`() {
        // arrival order would put the two strongest together
        solo(1400); solo(1380); solo(1360); solo(1340)

        val sides = queue.tick().single().sides
        val means = sides.map { side -> side.sumOf { it.rating } / side.size }
        assertTrue(
            kotlin.math.abs(means[0] - means[1]) <= 10,
            "sides should be within a few points, were $means",
        )
    }

    @Test
    fun `two full parties play as themselves`() {
        val (leftId, left) = party(1000, 1050)
        val (rightId, right) = party(1020, 990)

        val pairing = queue.tick().single()

        assertEquals(
            setOf(left.toSet(), right.toSet()),
            pairing.sides.map { side -> side.map { it.uuid }.toSet() }.toSet(),
            "each party must stay together as one side",
        )
        assertEquals(setOf(leftId, rightId), pairing.partyIds.filterNotNull().toSet())
    }

    @Test
    fun `a party is never split across sides`() {
        val (_, duo) = party(1000, 1000)
        solo(1000)
        solo(1000)

        val pairing = queue.tick().single()
        val sides = pairing.sides.map { side -> side.map { it.uuid }.toSet() }
        assertTrue(
            sides.any { it == duo.toSet() },
            "the party's two players must share a side, sides were $sides",
        )
    }

    @Test
    fun `a party larger than one side is refused`() {
        val id = UUID.randomUUID()
        val members = List(3) { UUID.randomUUID() to 1000 }

        assertFalse(queue.enqueueParty(id, members, MatchFormat.RANKED_2V2))
        assertEquals(0, queue.size)
    }

    @Test
    fun `a party-only format cannot enter the public queue`() {
        assertFalse(queue.enqueue(UUID.randomUUID(), 1000, MatchFormat.PARTY_FFA))
        assertFalse(queue.enqueue(UUID.randomUUID(), 1000, MatchFormat.PARTY_TEAMS))
    }

    @Test
    fun `a party ticket is matched on its median rating`() {
        // mean 1500, median 1000: a mean would let this stack meet a 1500 party
        val (_, _) = party(3000, 1000, 1000, format = MatchFormat.RANKED_3V3)
        party(1000, 1000, 1000, format = MatchFormat.RANKED_3V3)

        assertEquals(1, queue.tick().size, "medians of 1000 and 1000 are well inside the band")
    }

    @Test
    fun `parties too far apart in median rating do not meet`() {
        party(1000, 1000)
        party(1900, 1900)

        assertTrue(queue.tick().isEmpty(), "a 900-point gap is outside the opening band")
    }

    @Test
    fun `the band widens until distant parties can meet`() {
        party(1000, 1000)
        party(1500, 1500)

        assertTrue(queue.tick().isEmpty())
        now = now.plusSeconds(120) // band 100 + 120*5 = 700 > 500
        assertEquals(1, queue.tick().size)
    }

    @Test
    fun `any member leaving takes the whole party out of the queue`() {
        val (_, members) = party(1000, 1000)

        assertTrue(queue.remove(members.first()))
        assertEquals(0, queue.size)
        assertFalse(queue.contains(members[1]), "the other member must not be left waiting alone")
    }

    @Test
    fun `a player already queued cannot join a party ticket`() {
        val lone = solo(1000)

        assertFalse(
            queue.enqueueParty(UUID.randomUUID(), listOf(lone to 1000, UUID.randomUUID() to 1000), MatchFormat.RANKED_2V2)
        )
        assertEquals(1, queue.size)
    }

    @Test
    fun `queue position and headcount are measured in players`() {
        val (_, first) = party(1000, 1000)
        val second = solo(1000)

        assertEquals(1, queue.positionOf(first[0]))
        assertEquals(1, queue.positionOf(first[1]))
        assertEquals(3, queue.positionOf(second), "two players are ahead of them")
        assertEquals(3, queue.sizeOf(MatchFormat.RANKED_2V2))
    }

    @Test
    fun `eta reflects how many more players are needed`() {
        val alone = solo(1000)
        assertNull(queue.etaSeconds(alone), "nobody else is waiting")

        solo(1000); solo(1000)
        assertNull(queue.etaSeconds(alone), "three players is still one short of a 2v2")

        solo(1000)
        assertEquals(0L, queue.etaSeconds(alone), "the fourth arrival completes the match now")
    }

    @Test
    fun `reinstating a ticket restores it unchanged`() {
        val (id, members) = party(1000, 1000)
        party(1000, 1000)
        val pairing = queue.tick().single()
        assertEquals(0, queue.size)

        val ticket = pairing.tickets.single { it.id == id }
        assertTrue(queue.reinstate(ticket))

        assertEquals(id, queue.partyOf(members[0]))
        assertEquals(id, queue.partyOf(members[1]))
        assertEquals(2, queue.sizeOf(MatchFormat.RANKED_2V2))
        // the players were not at fault, so they keep their place and their band
        assertEquals(0L, queue.waitedSeconds(members[0]))
    }

    @Test
    fun `a 1v1 still comes out as a solo pairing`() {
        val a = solo(1000, MatchFormat.LOCKOUT_1V1)
        val b = solo(1000, MatchFormat.LOCKOUT_1V1)

        val pairing = queue.tick().single()
        assertTrue(pairing.isSolo)
        val match = pairing.asQueueMatch()
        assertEquals(setOf(a, b), setOf(match.playerA.uuid, match.playerB.uuid))
    }

    @Test
    fun `formats never mix`() {
        solo(1000, MatchFormat.RANKED_2V2)
        solo(1000, MatchFormat.RANKED_2V2)
        solo(1000, MatchFormat.CASUAL_2V2)
        solo(1000, MatchFormat.CASUAL_2V2)

        assertTrue(queue.tick().isEmpty(), "two of each is one short of either match")
        assertEquals(2, queue.sizeOf(MatchFormat.RANKED_2V2))
        assertEquals(2, queue.sizeOf(MatchFormat.CASUAL_2V2))
    }
}
