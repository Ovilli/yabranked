package dev.yabranked.backend.queue

import dev.yabranked.proto.MatchFormat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil

data class QueueEntry(
    val uuid: UUID,
    val rating: Int,
    val format: MatchFormat,
    val enqueuedAt: Instant,
)

data class QueueMatch(
    val playerA: QueueEntry,
    val playerB: QueueEntry,
)

/**
 * One unit waiting in the queue: a lone player, or a party that queues and
 * matches together and is never split across sides.
 */
data class QueueTicket(
    /** The party's id, or the player's own uuid for a solo ticket. */
    val id: UUID,
    val partyId: UUID?,
    val members: List<QueueEntry>,
    val format: MatchFormat,
    val enqueuedAt: Instant,
) {
    init {
        require(members.isNotEmpty()) { "a queue ticket needs at least one player" }
    }

    val size: Int get() = members.size

    /**
     * The ticket's matchmaking rating: the *median* of its members, not the
     * mean. A four-stack of one 2000 and three 900s has a mean that flatters it
     * and a median that describes who its opponents will actually be playing
     * against for most of the match — and the median is what parties were
     * specified to be matched on.
     */
    val rating: Int = members.map { it.rating }.sorted().let { sorted ->
        val middle = sorted.size / 2
        if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }
}

/**
 * One assembled match, side-ordered. A 1v1 is the degenerate case of two sides
 * of one, so the solo path reads [playerA]/[playerB] exactly as it always did.
 */
data class QueuePairing(
    val format: MatchFormat,
    val sides: List<List<QueueEntry>>,
    /** The tickets consumed, so a failed match creation can put them back. */
    val tickets: List<QueueTicket>,
) {
    val playerA: QueueEntry get() = sides[0].first()
    val playerB: QueueEntry get() = sides[1].first()
    val players: List<QueueEntry> get() = sides.flatten()

    /** The classic two-players shape, which [QueueMatch] can describe. */
    val isSolo: Boolean get() = sides.size == 2 && sides.all { it.size == 1 }

    fun asQueueMatch(): QueueMatch = QueueMatch(playerA, playerB)

    /** The party each side came from, null where it was assembled from strangers. */
    val partyIds: List<UUID?>
        get() = sides.map { side ->
            tickets.firstOrNull { ticket -> ticket.partyId != null && ticket.members == side }?.partyId
        }
}

/**
 * Matchmaking with MMR-band expansion: each ticket accepts opponents
 * within [initialBand] rating points, widening by [bandPerSecond] the
 * longer it waits (capped at [maxBand]). A pairing forms when the tickets'
 * current bands cover the rating gaps — the MCSR-style fairness/wait-time
 * trade-off is tuned entirely via these knobs.
 *
 * Team formats use the same bands: the queue assembles exactly
 * [MatchFormat.playerCount] players out of tickets the seeker accepts, then
 * splits them into equal sides. Parties are never split, which is the whole
 * reason tickets exist rather than a flat list of players.
 *
 * Not thread-safe; callers synchronize (the service ticks it from one coroutine).
 */
class MatchmakingQueue(
    private val initialBand: Int = 100,
    private val bandPerSecond: Double = 5.0,
    private val maxBand: Int = 1000,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val tickets = LinkedHashMap<UUID, QueueTicket>()

    /** Player uuid to the id of the ticket they are waiting in. */
    private val ticketOf = HashMap<UUID, UUID>()

    /**
     * Positions and headcounts derived from [tickets], rebuilt on the first
     * read after a mutation. Every waiting player asks for their own position
     * once a second; deriving each one by walking the queue made a tick
     * quadratic in queue size.
     */
    private var index: QueueIndex? = null

    /**
     * Both views are per format: players only ever match inside their own
     * format, so a position counted across all of them would be a number the
     * player can never reach — and could exceed the count shown next to it.
     */
    private class QueueIndex(
        val positions: Map<UUID, Int>,
        val sizes: Map<MatchFormat, Int>,
    )

    private fun index(): QueueIndex = index ?: buildIndex().also { index = it }

    private fun buildIndex(): QueueIndex {
        val positions = HashMap<UUID, Int>(ticketOf.size)
        val sizes = HashMap<MatchFormat, Int>()
        // iteration order == enqueue order, so the running count per format is
        // exactly that ticket's place in its own line. Counted in players, not
        // tickets: "4th of 9" has to mean the same thing on both sides of "of".
        for (ticket in tickets.values) {
            val ahead = sizes[ticket.format] ?: 0
            sizes[ticket.format] = ahead + ticket.size
            for (member in ticket.members) positions[member.uuid] = ahead + 1
        }
        return QueueIndex(positions, sizes)
    }

    /** Players waiting, across every format. */
    val size: Int get() = ticketOf.size

    /** Tickets waiting, across every format. */
    val ticketCount: Int get() = tickets.size

    fun contains(uuid: UUID): Boolean = uuid in ticketOf

    private fun ticketFor(uuid: UUID): QueueTicket? = ticketOf[uuid]?.let(tickets::get)

    /** The format this player is waiting in, or null if they are not queued. */
    fun formatOf(uuid: UUID): MatchFormat? = ticketFor(uuid)?.format

    /** The party this player is queued with, or null when they queued alone. */
    fun partyOf(uuid: UUID): UUID? = ticketFor(uuid)?.partyId

    /** 1-based place in this player's own format queue; 0 if not queued. */
    fun positionOf(uuid: UUID): Int = index().positions[uuid] ?: 0

    /** How many players are waiting for [format]. */
    fun sizeOf(format: MatchFormat): Int = index().sizes[format] ?: 0

    fun waitedSeconds(uuid: UUID): Long =
        ticketFor(uuid)?.let { Duration.between(it.enqueuedAt, clock.instant()).seconds } ?: 0

    /** Returns false if the player is already queued. */
    fun enqueue(uuid: UUID, rating: Int, format: MatchFormat): Boolean =
        enqueueTicket(id = uuid, partyId = null, format = format, members = listOf(uuid to rating))

    /**
     * Queue a party as one unit. Returns false when any member is already
     * queued, when the party is too large for one side of [format], or when
     * [format] is not something the public matchmaker hands out.
     */
    fun enqueueParty(partyId: UUID, members: List<Pair<UUID, Int>>, format: MatchFormat): Boolean =
        enqueueTicket(id = partyId, partyId = partyId, format = format, members = members)

    private fun enqueueTicket(
        id: UUID,
        partyId: UUID?,
        format: MatchFormat,
        members: List<Pair<UUID, Int>>,
    ): Boolean {
        if (members.isEmpty()) return false
        if (id in tickets) return false
        // A party-only format is started by its party directly; letting one into
        // the public queue would mean waiting forever for an opponent that by
        // definition never queues.
        if (!format.playable || format.partyOnly) return false
        // A party bigger than a side cannot be kept together, and splitting it is
        // exactly what a party queue promises not to do.
        if (members.size > format.teamSize) return false
        if (members.any { it.first in ticketOf }) return false
        if (members.distinctBy { it.first }.size != members.size) return false

        val now = clock.instant()
        val ticket = QueueTicket(
            id = id,
            partyId = partyId,
            members = members.map { (uuid, rating) -> QueueEntry(uuid, rating, format, now) },
            format = format,
            enqueuedAt = now,
        )
        tickets[id] = ticket
        for (member in ticket.members) ticketOf[member.uuid] = id
        index = null
        return true
    }

    /**
     * Put a ticket [tick] handed out back into the queue, unchanged — same id,
     * same party, same [QueueTicket.enqueuedAt]. Used when creating the match
     * failed: the players were not at fault, so they do not go to the back of
     * the line and their band does not reset.
     */
    fun reinstate(ticket: QueueTicket): Boolean {
        if (ticket.id in tickets) return false
        if (ticket.members.any { it.uuid in ticketOf }) return false
        tickets[ticket.id] = ticket
        for (member in ticket.members) ticketOf[member.uuid] = ticket.id
        index = null
        return true
    }

    /**
     * Take this player's ticket out of the queue. A party member leaving takes
     * the whole party with them — a half-party left waiting for a 2v2 slot it
     * can no longer fill is the bug this prevents.
     */
    fun remove(uuid: UUID): Boolean = removeTicket(ticketOf[uuid] ?: return false)

    fun removeTicket(id: UUID): Boolean {
        val ticket = tickets.remove(id) ?: return false
        for (member in ticket.members) ticketOf.remove(member.uuid)
        index = null
        return true
    }

    private fun bandOf(ticket: QueueTicket, now: Instant): Int {
        val waited = Duration.between(ticket.enqueuedAt, now).seconds
        return (initialBand + (waited * bandPerSecond)).toInt().coerceAtMost(maxBand)
    }

    /**
     * Seconds until this player's soonest possible pairing, or null when the
     * queue can never produce one (nobody in range, or not enough players).
     *
     * The client used to invent this number from the headcount alone, which
     * meant it promised "any moment" to a player whose only opponent was 400
     * rating away. This inverts [accepts]: for each candidate, how long until
     * *both* bands reach the gap — the less-waited ticket is what binds — and
     * for team formats, how long until enough of them have.
     */
    fun etaSeconds(uuid: UUID): Long? {
        val self = ticketFor(uuid) ?: return null
        val now = clock.instant()
        val needed = self.format.playerCount - self.size
        if (needed <= 0) return null

        var have = 0
        var eta = 0L
        val candidates = tickets.values.asSequence()
            .filter { it.id != self.id && it.format == self.format && it.size <= needed }
            .mapNotNull { candidate -> pairableIn(self, candidate, now)?.let { it to candidate } }
            .sortedBy { it.first }
        for ((wait, candidate) in candidates) {
            if (have + candidate.size > needed) continue
            have += candidate.size
            eta = maxOf(eta, wait)
            if (have == needed) return eta
        }
        return null
    }

    /** Seconds until [a] and [b] accept each other, or null if the cap forbids it. */
    private fun pairableIn(a: QueueTicket, b: QueueTicket, now: Instant): Long? {
        val gap = abs(a.rating - b.rating)
        if (gap > maxBand) return null
        return maxOf(waitNeeded(a, gap, now), waitNeeded(b, gap, now))
    }

    /** Seconds [ticket] still has to wait for its band to reach [gap]. */
    private fun waitNeeded(ticket: QueueTicket, gap: Int, now: Instant): Long {
        if (gap <= initialBand) return 0
        val required = ceil((gap - initialBand) / bandPerSecond).toLong()
        return (required - Duration.between(ticket.enqueuedAt, now).seconds).coerceAtLeast(0)
    }

    private fun accepts(a: QueueTicket, b: QueueTicket, now: Instant): Boolean {
        if (a.format != b.format) return false
        val gap = abs(a.rating - b.rating)
        return gap <= bandOf(a, now) && gap <= bandOf(b, now)
    }

    /**
     * Find every match currently possible and remove the matched tickets.
     * Longest-waiting tickets get priority; among candidates, the closest
     * rating wins.
     */
    fun tick(): List<QueuePairing> {
        val now = clock.instant()
        val pairings = mutableListOf<QueuePairing>()

        // iteration order == enqueue order == wait-time priority
        val waiting = tickets.values.toMutableList()
        while (waiting.isNotEmpty()) {
            val seeker = waiting.removeFirst()
            val pool = assemble(seeker, waiting, now) ?: continue
            val sides = formSides(seeker.format, pool) ?: continue

            for (ticket in pool) {
                waiting.remove(ticket)
                removeTicket(ticket.id)
            }
            pairings += QueuePairing(format = seeker.format, sides = sides, tickets = pool)
        }

        return pairings
    }

    /**
     * Tickets adding up to exactly one match, seeker included, or null when the
     * queue cannot fill one right now.
     *
     * Exact fit only: a 2v2 will not start with three players, and a ticket that
     * would overflow the match is skipped rather than split.
     */
    private fun assemble(seeker: QueueTicket, pool: List<QueueTicket>, now: Instant): List<QueueTicket>? {
        val needed = seeker.format.playerCount
        // A ticket that already fills the match has nobody to play against.
        if (seeker.size >= needed) return null

        val chosen = mutableListOf(seeker)
        var have = seeker.size
        val candidates = pool
            .filter { accepts(seeker, it, now) }
            .sortedBy { abs(it.rating - seeker.rating) }
        for (candidate in candidates) {
            if (have + candidate.size > needed) continue
            chosen += candidate
            have += candidate.size
            if (have == needed) break
        }
        return chosen.takeIf { have == needed }
    }

    /**
     * Split assembled tickets into equal sides, never splitting a party.
     *
     * Two shapes come out of here. When the tickets are already exactly one
     * side each, they play as they queued — that is party versus party, equal
     * size guaranteed by the ticket sizes and comparable median rating
     * guaranteed by [accepts]. Otherwise the tickets are packed into the sides
     * emptiest-and-weakest-first, which is a snake draft when everyone queued
     * alone and the closest thing to one when some of them did not.
     *
     * Null when the tickets cannot tile the sides at all (three pairs into two
     * sides of three, say); the caller leaves them queued to try again.
     */
    private fun formSides(format: MatchFormat, pool: List<QueueTicket>): List<List<QueueEntry>>? {
        val teamSize = format.teamSize
        val teamCount = format.teamCount

        if (pool.size == teamCount && pool.all { it.size == teamSize }) {
            return pool.map { it.members }
        }

        val sides = MutableList(teamCount) { mutableListOf<QueueEntry>() }
        // Largest tickets first: they are the ones with no room to be placed
        // later, and strongest first so the balancing tie-break has something
        // left to correct with.
        for (ticket in pool.sortedWith(compareByDescending<QueueTicket> { it.size }.thenByDescending { it.rating })) {
            val target = sides
                .filter { it.size + ticket.size <= teamSize }
                .minWithOrNull(compareBy({ it.size }, { side -> side.sumOf { it.rating } }))
                ?: return null
            target += ticket.members
        }
        if (sides.any { it.size != teamSize }) return null
        return sides
    }
}
