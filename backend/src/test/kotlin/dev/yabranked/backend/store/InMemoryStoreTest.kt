package dev.yabranked.backend.store

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The batch accessors that exist purely to collapse N+1 query patterns. They
 * are only worth anything if they return the same answers the per-row lookups
 * did, and the Postgres versions of them cannot run here (no Docker), so at
 * least hold the in-memory ones to the contract.
 */
class InMemoryStoreTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val season = 1

    private fun player(name: String): UUID {
        val uuid = UUID.randomUUID()
        players.upsertPlayer(PlayerRecord(uuid, name, createdAt = Instant.now()))
        return uuid
    }

    private fun stats(uuid: UUID, rating: Int, played: Int) {
        players.upsertStats(
            SeasonStats(uuid, season, rating, played, wins = played, losses = 0, draws = 0)
        )
    }

    private fun match(
        a: UUID,
        b: UUID,
        outcome: MatchOutcome?,
        status: MatchStatus = MatchStatus.COMPLETED,
        createdAt: Instant = Instant.now(),
    ): MatchRecord {
        val record = MatchRecord(
            id = UUID.randomUUID(),
            season = season,
            format = MatchFormat.LOCKOUT_1V1,
            settings = MatchSettings(
                format = MatchFormat.LOCKOUT_1V1,
                worldSeed = 1,
                cardSeed = 2,
                timeLimitSeconds = 60,
            ),
            playerA = a,
            playerB = b,
            status = status,
            serverToken = "t",
            outcome = outcome,
            ratingABefore = 1000,
            ratingBBefore = 1000,
            ratingAAfter = null,
            ratingBAfter = null,
            createdAt = createdAt,
            completedAt = null,
        )
        matches.insert(record)
        return record
    }

    @Test
    fun `getPlayers returns one entry per known uuid and skips the rest`() {
        val a = player("Anna")
        val b = player("Ben")
        val unknown = UUID.randomUUID()

        val found = players.getPlayers(listOf(a, b, unknown))

        assertEquals(setOf(a, b), found.keys)
        assertEquals("Anna", found[a]?.name)
    }

    @Test
    fun `getPlayers on an empty request does not fail`() {
        // the history route calls this with no rows when a player has no matches
        assertTrue(players.getPlayers(emptyList()).isEmpty())
    }

    @Test
    fun `leaderboard carries each row's account with it`() {
        val a = player("Anna")
        val b = player("Ben")
        stats(a, rating = 1200, played = 10)
        stats(b, rating = 1100, played = 10)

        val ladder = players.leaderboard(season, limit = 10, minMatches = 1)

        assertEquals(listOf("Anna", "Ben"), ladder.map { it.player?.name })
        assertEquals(listOf(1200, 1100), ladder.map { it.stats.rating })
    }

    @Test
    fun `leaderboard agrees with topByRating on ordering and filtering`() {
        val a = player("Anna")
        val b = player("Ben")
        val c = player("Cara")
        stats(a, rating = 1200, played = 10)
        stats(b, rating = 1300, played = 2)
        stats(c, rating = 1400, played = 1)

        val top = players.topByRating(season, limit = 10, minMatches = 5)
        val ladder = players.leaderboard(season, limit = 10, minMatches = 5)

        assertEquals(top.map { it.uuid }, ladder.map { it.stats.uuid })
        assertEquals(listOf(a), ladder.map { it.stats.uuid })
    }

    @Test
    fun `recentDecided skips voided and still running matches`() {
        val a = player("Anna")
        val b = player("Ben")
        match(a, b, MatchOutcome.TEAM_A_WIN)
        match(a, b, MatchOutcome.VOID, status = MatchStatus.VOIDED)
        match(a, b, null, status = MatchStatus.ACTIVE)
        match(a, b, MatchOutcome.TEAM_B_WIN)

        val decided = matches.recentDecided(a, season, limit = 10)

        assertEquals(2, decided.size, "a void or a live match is not a streak result")
        assertTrue(decided.all { it.status == MatchStatus.COMPLETED })
    }

    @Test
    fun `recentDecided is newest first and honours the limit`() {
        val a = player("Anna")
        val b = player("Ben")
        val old = match(a, b, MatchOutcome.TEAM_A_WIN, createdAt = Instant.now().minusSeconds(600))
        val new = match(a, b, MatchOutcome.TEAM_A_WIN, createdAt = Instant.now())

        val decided = matches.recentDecided(a, season, limit = 1)

        assertEquals(listOf(new.id), decided.map { it.id }, "the streak reads from the newest end")
        assertTrue(old.id !in decided.map { it.id })
    }

    @Test
    fun `between returns only the two players' own decided matches`() {
        val a = player("Anna")
        val b = player("Ben")
        val c = player("Cara")
        val head = match(a, b, MatchOutcome.TEAM_A_WIN)
        match(a, c, MatchOutcome.TEAM_A_WIN)
        match(a, b, MatchOutcome.VOID, status = MatchStatus.VOIDED)

        val versus = matches.between(a, b, season, limit = 10)

        assertEquals(listOf(head.id), versus.map { it.id })
    }

    @Test
    fun `between does not care which way round the players are given`() {
        val a = player("Anna")
        val b = player("Ben")
        match(a, b, MatchOutcome.TEAM_A_WIN)

        assertEquals(
            matches.between(a, b, season, limit = 10).map { it.id },
            matches.between(b, a, season, limit = 10).map { it.id },
        )
    }

    @Test
    fun `lifetimeStats folds every season together`() {
        val a = player("Anna")
        players.upsertStats(SeasonStats(a, 1, rating = 1200, matchesPlayed = 10, wins = 7, losses = 3, draws = 0))
        players.upsertStats(SeasonStats(a, 2, rating = 900, matchesPlayed = 4, wins = 1, losses = 3, draws = 0))

        val lifetime = players.lifetimeStats(a)

        assertEquals(14, lifetime.matchesPlayed)
        assertEquals(8, lifetime.wins)
        // peak is the best ever reached, not the current season's
        assertEquals(1200, lifetime.peakRating)
    }

    @Test
    fun `lifetimeStats for an account with no rows is all zeroes rather than null`() {
        val lifetime = players.lifetimeStats(UUID.randomUUID())

        assertEquals(0, lifetime.matchesPlayed)
        assertEquals(0, lifetime.peakRating)
    }

    @Test
    fun `unsettled finds exactly the matches still owed a result`() {
        val a = player("Anna")
        val b = player("Ben")
        val pending = match(a, b, null, status = MatchStatus.PENDING)
        val active = match(a, b, null, status = MatchStatus.ACTIVE)
        match(a, b, MatchOutcome.TEAM_A_WIN)
        match(a, b, MatchOutcome.VOID, status = MatchStatus.VOIDED)

        val orphans = matches.unsettled().map { it.id }.toSet()

        assertEquals(setOf(pending.id, active.id), orphans)
    }

    @Test
    fun `rankOf ties share the better rank and an unranked player has none`() {
        val a = player("Anna")
        val b = player("Ben")
        val c = player("Cara")
        stats(a, rating = 1200, played = 10)
        stats(b, rating = 1200, played = 10)
        stats(c, rating = 1000, played = 10)

        assertEquals(1, players.rankOf(a, season, minMatches = 1))
        assertEquals(1, players.rankOf(b, season, minMatches = 1))
        assertEquals(3, players.rankOf(c, season, minMatches = 1), "a tie consumes the rank below it")
        assertNull(players.rankOf(UUID.randomUUID(), season, minMatches = 1))
    }
}
