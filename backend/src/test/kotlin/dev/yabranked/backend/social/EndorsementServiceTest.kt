package dev.yabranked.backend.social

import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryEndorsementStore
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryModeStatsStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.proto.EndorsementCategory
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndorsementServiceTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val endorsements = InMemoryEndorsementStore()
    private val seasons = SeasonService()
    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val matchService = MatchService(
        players, matches, EloRatingSystem(), seasons,
        clock = clock,
        modeStats = InMemoryModeStatsStore(),
    )
    private val service = EndorsementService(endorsements, matches, clock)

    private fun team(size: Int) = List(size) {
        UUID.randomUUID().also { uuid -> matchService.getOrCreatePlayer(uuid, "P$uuid") }
    }

    /** Creates and settles one match, returning its id and rosters. */
    private fun playedMatch(
        format: MatchFormat = MatchFormat.RANKED_2V2,
        sides: List<List<UUID>> = listOf(team(2), team(2)),
        outcome: MatchOutcome = MatchOutcome.TEAM_A_WIN,
    ): Pair<UUID, List<List<UUID>>> {
        val match = matchService.createTeamMatch(
            MatchService.TeamMatchRequest(format = format, teams = sides)
        )
        matchService.settle(
            MatchResultReport(match.id.toString(), outcome, 900, 8, 5),
            match.serverToken,
        )
        return match.id to sides
    }

    @Test
    fun `teammates may endorse each other`() {
        val (matchId, sides) = playedMatch()
        val (me, mate) = sides[0]

        val result = service.endorse(me, matchId, listOf(mate), EndorsementCategory.TEAMWORK)

        assertEquals(1, assertIs<EndorsementService.Result.Ok>(result).awarded)
        assertEquals(1, service.summaryFor(mate).total)
        assertEquals(mapOf("teamwork" to 1), service.summaryFor(mate).categories)
    }

    @Test
    fun `a solo mode has nobody to endorse`() {
        val a = team(1).single()
        val b = team(1).single()
        val (matchId, _) = playedMatch(MatchFormat.LOCKOUT_1V1, listOf(listOf(a), listOf(b)))

        assertNull(service.promptFor(a, matchId), "a 1v1 has no teammates")
        assertFalse(service.canEndorse(a, matchId))
        assertIs<EndorsementService.Result.Rejected>(
            service.endorse(a, matchId, listOf(b), EndorsementCategory.TEAMWORK)
        )
    }

    @Test
    fun `opponents cannot be endorsed`() {
        val (matchId, sides) = playedMatch()
        val me = sides[0].first()
        val enemy = sides[1].first()

        val result = service.endorse(me, matchId, listOf(enemy), EndorsementCategory.SHOTCALLING)
        assertIs<EndorsementService.Result.Rejected>(result)
        assertEquals(0, service.summaryFor(enemy).total)
    }

    @Test
    fun `you cannot endorse yourself`() {
        val (matchId, sides) = playedMatch()
        val me = sides[0].first()

        assertIs<EndorsementService.Result.Rejected>(
            service.endorse(me, matchId, listOf(me), EndorsementCategory.TEAMWORK)
        )
        assertEquals(0, service.summaryFor(me).total)
    }

    @Test
    fun `someone who was not in the match cannot endorse`() {
        val (matchId, sides) = playedMatch()
        val outsider = team(1).single()

        assertNull(service.promptFor(outsider, matchId))
        assertIs<EndorsementService.Result.Rejected>(
            service.endorse(outsider, matchId, listOf(sides[0].first()), EndorsementCategory.TEAMWORK)
        )
    }

    @Test
    fun `a teammate may only be endorsed once per match`() {
        val (matchId, sides) = playedMatch()
        val (me, mate) = sides[0]

        assertIs<EndorsementService.Result.Ok>(
            service.endorse(me, matchId, listOf(mate), EndorsementCategory.TEAMWORK)
        )
        assertIs<EndorsementService.Result.Rejected>(
            service.endorse(me, matchId, listOf(mate), EndorsementCategory.SHOTCALLING)
        )
        assertEquals(1, service.summaryFor(mate).total, "a second category is not a second endorsement")
    }

    @Test
    fun `the same pair endorsing across matches keeps counting`() {
        val sides = listOf(team(2), team(2))
        val (me, mate) = sides[0]
        repeat(3) {
            val (matchId, _) = playedMatch(sides = sides)
            service.endorse(me, matchId, listOf(mate), EndorsementCategory.TEAMWORK)
        }
        assertEquals(3, service.summaryFor(mate).total)
    }

    @Test
    fun `the window closes`() {
        val (matchId, sides) = playedMatch()
        val (me, mate) = sides[0]

        now = now.plus(Duration.ofHours(25))

        assertNull(service.promptFor(me, matchId))
        assertIs<EndorsementService.Result.Rejected>(
            service.endorse(me, matchId, listOf(mate), EndorsementCategory.TEAMWORK)
        )
    }

    @Test
    fun `a voided match cannot be endorsed`() {
        val sides = listOf(team(2), team(2))
        val (matchId, _) = playedMatch(sides = sides, outcome = MatchOutcome.VOID)

        assertNull(service.promptFor(sides[0].first(), matchId))
    }

    @Test
    fun `a prompt disappears once the player has endorsed`() {
        val (matchId, sides) = playedMatch()
        val (me, mate) = sides[0]

        assertEquals(listOf(mate), service.promptFor(me, matchId))
        service.endorse(me, matchId, listOf(mate), EndorsementCategory.TEAMWORK)
        assertNull(service.promptFor(me, matchId))
    }

    @Test
    fun `one call may endorse several teammates`() {
        val sides = listOf(team(3), team(3))
        val (matchId, _) = playedMatch(MatchFormat.RANKED_3V3, sides)
        val (me, mateA, mateB) = sides[0]

        val result = service.endorse(me, matchId, listOf(mateA, mateB), EndorsementCategory.TEAMWORK)
        assertEquals(2, assertIs<EndorsementService.Result.Ok>(result).awarded)
        assertEquals(1, service.summaryFor(mateA).total)
        assertEquals(1, service.summaryFor(mateB).total)
    }

    @Test
    fun `a list containing one opponent writes nothing at all`() {
        val sides = listOf(team(3), team(3))
        val (matchId, _) = playedMatch(MatchFormat.RANKED_3V3, sides)
        val me = sides[0].first()

        val result = service.endorse(
            me, matchId,
            listOf(sides[0][1], sides[1][0]),
            EndorsementCategory.TEAMWORK,
        )
        assertIs<EndorsementService.Result.Rejected>(result)
        assertEquals(0, service.summaryFor(sides[0][1]).total, "the valid half must not slip through")
    }

    @Test
    fun `levels rise with the endorsement total`() {
        assertEquals(1, service.levelFor(0))
        assertEquals(1, service.levelFor(4))
        assertEquals(2, service.levelFor(5))
        assertEquals(3, service.levelFor(15))
        assertEquals(service.maxLevel, service.levelFor(100_000))
    }

    @Test
    fun `progress runs from zero to one inside a level`() {
        assertEquals(0f, service.progressFor(0))
        assertEquals(0.8f, service.progressFor(4), 0.001f)
        assertEquals(0f, service.progressFor(5), 0.001f)
        assertEquals(1f, service.progressFor(100_000))
    }

    @Test
    fun `summaries for many players agree with the individual ones`() {
        val (matchId, sides) = playedMatch()
        val (me, mate) = sides[0]
        service.endorse(me, matchId, listOf(mate), EndorsementCategory.TEAMWORK)

        val bulk = service.summariesFor(listOf(me, mate))
        assertEquals(service.summaryFor(mate).total, bulk.getValue(mate).total)
        assertEquals(service.summaryFor(mate).level, bulk.getValue(mate).level)
        assertEquals(0, bulk.getValue(me).total)
    }

    @Test
    fun `an unknown match is refused`() {
        val me = team(1).single()
        assertIs<EndorsementService.Result.Rejected>(
            service.endorse(me, UUID.randomUUID(), listOf(me), EndorsementCategory.TEAMWORK)
        )
    }

    @Test
    fun `endorsing nobody is refused`() {
        val (matchId, sides) = playedMatch()
        assertIs<EndorsementService.Result.Rejected>(
            service.endorse(sides[0].first(), matchId, emptyList(), EndorsementCategory.TEAMWORK)
        )
    }
}
