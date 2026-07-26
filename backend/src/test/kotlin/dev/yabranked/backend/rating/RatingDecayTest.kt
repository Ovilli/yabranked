package dev.yabranked.backend.rating

import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.SeasonStats
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RatingDecayTest {

    private val decay = RatingDecay()
    private val policy = decay.policy
    private val now = Instant.parse("2026-06-01T00:00:00Z")

    private fun daysAgo(days: Long) = now.minus(Duration.ofDays(days))

    @Test
    fun `nothing is owed inside the grace period`() {
        val charge = decay.charge(
            rating = 2000,
            lastPlayedAt = daysAgo(policy.graceDays - 1),
            decayedThrough = null,
            now = now,
        )

        assertNull(charge)
    }

    @Test
    fun `idle days past the grace period cost points`() {
        val charge = decay.charge(
            rating = 2000,
            lastPlayedAt = daysAgo(policy.graceDays + 10),
            decayedThrough = null,
            now = now,
        )!!

        assertEquals(2000 - 10 * policy.pointsPerDay, charge.rating)
    }

    @Test
    fun `a rating in the protected band never decays`() {
        // decay keeps the visible top of the ladder honest; it is not a
        // punishment for the casual middle taking a month off
        val charge = decay.charge(
            rating = policy.protectedRating,
            lastPlayedAt = daysAgo(365),
            decayedThrough = null,
            now = now,
        )

        assertNull(charge)
    }

    @Test
    fun `decay stops at the protected rating rather than falling through it`() {
        val charge = decay.charge(
            rating = policy.protectedRating + 20,
            lastPlayedAt = daysAgo(policy.graceDays + 1000),
            decayedThrough = null,
            now = now,
        )!!

        assertEquals(policy.protectedRating, charge.rating)
    }

    @Test
    fun `a null lastPlayedAt is unknown, not idle forever`() {
        // rows written before the column existed, and rows a rollover seeded
        val charge = decay.charge(rating = 2000, lastPlayedAt = null, decayedThrough = null, now = now)

        assertNull(charge)
    }

    @Test
    fun `sweeping twice in a row does not bill the same days again`() {
        val first = decay.charge(
            rating = 2000,
            lastPlayedAt = daysAgo(policy.graceDays + 10),
            decayedThrough = null,
            now = now,
        )!!

        val second = decay.charge(
            rating = first.rating,
            lastPlayedAt = daysAgo(policy.graceDays + 10),
            decayedThrough = first.chargedThrough,
            now = now,
        )

        assertNull(second, "the same idle window was charged twice")
    }

    @Test
    fun `a partial day is not charged until it completes`() {
        val charge = decay.charge(
            rating = 2000,
            lastPlayedAt = daysAgo(policy.graceDays).minus(Duration.ofHours(12)),
            decayedThrough = null,
            now = now,
        )

        assertNull(charge, "half a day should not cost a whole day's points")
    }
}

class DecaySweepTest {

    private val players = InMemoryPlayerStore()
    private val seasons = SeasonService()
    private val rating = EloRatingSystem()
    private var now: Instant = Instant.parse("2026-06-01T00:00:00Z")
    private val clock = object : Clock() {
        override fun instant() = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
    }
    private val sweep = DecaySweep(players, seasons, rating, clock = clock)
    private val policy = DecayPolicy()

    private fun ladderPlayer(rating: Int, lastPlayedDaysAgo: Long?, played: Int = 10): UUID {
        val uuid = UUID.randomUUID()
        players.upsertStats(
            SeasonStats(
                uuid, seasons.currentSeason, rating,
                matchesPlayed = played, wins = played, losses = 0, draws = 0,
                lastPlayedAt = lastPlayedDaysAgo?.let { now.minus(Duration.ofDays(it)) },
            )
        )
        return uuid
    }

    private fun ratingOf(uuid: UUID) = players.getStats(uuid, seasons.currentSeason)!!.rating

    @Test
    fun `an idle high rating drops and an active one does not`() {
        val idle = ladderPlayer(2000, lastPlayedDaysAgo = policy.graceDays + 5)
        val active = ladderPlayer(2000, lastPlayedDaysAgo = 1)

        val changed = sweep.sweep()

        assertEquals(1, changed)
        assertTrue(ratingOf(idle) < 2000, "the inactive rating never moved")
        assertEquals(2000, ratingOf(active))
    }

    @Test
    fun `players still in placements are left alone`() {
        // they hold no rank, so there is nothing to keep honest
        val unplaced = ladderPlayer(2000, lastPlayedDaysAgo = 365, played = 1)

        sweep.sweep()

        assertEquals(2000, ratingOf(unplaced))
    }

    @Test
    fun `repeated sweeps on the same day charge once`() {
        val idle = ladderPlayer(2000, lastPlayedDaysAgo = policy.graceDays + 5)

        sweep.sweep()
        val afterFirst = ratingOf(idle)
        sweep.sweep()

        assertEquals(afterFirst, ratingOf(idle), "a second sweep billed the same idle days again")
    }

    @Test
    fun `decay resumes as more idle days accumulate`() {
        val idle = ladderPlayer(2000, lastPlayedDaysAgo = policy.graceDays + 1)
        sweep.sweep()
        val afterFirst = ratingOf(idle)

        now = now.plus(Duration.ofDays(5))
        sweep.sweep()

        assertEquals(afterFirst - 5 * policy.pointsPerDay, ratingOf(idle))
    }
}
