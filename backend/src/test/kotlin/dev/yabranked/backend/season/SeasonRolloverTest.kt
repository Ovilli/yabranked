package dev.yabranked.backend.season

import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.SeasonStats
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SeasonRolloverTest {

    private val players = InMemoryPlayerStore()
    private val rating = EloRatingSystem()
    private val rollover = SeasonRollover(players, rating)

    private fun placed(rating: Int, season: Int = 1, played: Int = 10): UUID {
        val uuid = UUID.randomUUID()
        players.upsertStats(
            SeasonStats(
                uuid, season, rating,
                matchesPlayed = played, wins = played, losses = 0, draws = 0,
                playtimeSeconds = 6000, peakRating = rating + 50,
                lastPlayedAt = Instant.now(),
            )
        )
        return uuid
    }

    @Test
    fun `a soft reset keeps half the distance from the initial rating`() {
        // a hard reset threw the ladder's signal away and left Netherite
        // players farming Coal opponents all of opening night
        val initial = rating.initialRating
        assertEquals(initial + 250, rollover.seedRating(initial + 500))
        assertEquals(initial - 250, rollover.seedRating(initial - 500))
        assertEquals(initial, rollover.seedRating(initial))
    }

    @Test
    fun `carried rows start the new season with fresh counters`() {
        val uuid = placed(1500)

        rollover.roll(from = 1, to = 2)

        val seeded = players.getStats(uuid, 2)!!
        assertEquals(rollover.seedRating(1500), seeded.rating)
        assertEquals(0, seeded.matchesPlayed)
        assertEquals(0, seeded.wins)
        assertEquals(0, seeded.playtimeSeconds)
        assertEquals(seeded.rating, seeded.peakRating, "peak must restart at the seed, not last season's high")
    }

    @Test
    fun `a seeded row still owes its placement matches`() {
        val uuid = placed(1500)

        rollover.roll(from = 1, to = 2)

        // the seed is a starting guess the new season can argue with
        assertEquals(0, players.getStats(uuid, 2)!!.matchesPlayed)
    }

    @Test
    fun `a seeded row is not immediately eligible for decay`() {
        val uuid = placed(2000)

        rollover.roll(from = 1, to = 2)

        val seeded = players.getStats(uuid, 2)!!
        assertNull(seeded.lastPlayedAt, "a row that was never played cannot be idle")
        assertNull(seeded.decayedThrough)
    }

    @Test
    fun `the closing season is left intact as its own final standings`() {
        val uuid = placed(1500)

        rollover.roll(from = 1, to = 2)

        val old = players.getStats(uuid, 1)!!
        assertEquals(1500, old.rating, "the closing season's rating was rewritten")
        assertEquals(10, old.matchesPlayed)
    }

    @Test
    fun `unplaced ratings are not carried`() {
        // an unfinished placement is not yet a claim about anything
        val unplaced = placed(1800, played = 2)

        rollover.roll(from = 1, to = 2)

        assertNull(players.getStats(unplaced, 2), "an unplaced rating was carried anyway")
    }
}
