package dev.yabranked.backend.match

import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryModeStatsStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settling matches with more than one player a side: which ladder moves, which
 * counters are written, and what a report has to say to be believed.
 */
class TeamMatchSettleTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val modeStats = InMemoryModeStatsStore()
    private val seasons = SeasonService()
    private val service = MatchService(
        players, matches, EloRatingSystem(), seasons, modeStats = modeStats,
    )

    private fun team(size: Int): List<UUID> = List(size) {
        UUID.randomUUID().also { uuid -> service.getOrCreatePlayer(uuid, "P$uuid") }
    }

    private fun create(
        format: MatchFormat = MatchFormat.RANKED_2V2,
        sides: List<List<UUID>> = listOf(team(2), team(2)),
        ranked: Boolean = format.ranked,
    ) = service.createTeamMatch(
        MatchService.TeamMatchRequest(format = format, teams = sides, ranked = ranked)
    )

    private fun report(
        matchId: UUID,
        outcome: MatchOutcome = MatchOutcome.TEAM_A_WIN,
        winningTeam: Int? = null,
        durationSeconds: Long = 900,
        teamScores: List<Int> = emptyList(),
        forfeitedBy: String? = null,
    ) = MatchResultReport(
        matchId = matchId.toString(),
        outcome = outcome,
        durationSeconds = durationSeconds,
        teamAScore = 8,
        teamBScore = 5,
        winningTeam = winningTeam,
        teamScores = teamScores,
        forfeitedBy = forfeitedBy,
    )

    @Test
    fun `a team match records its full roster`() {
        val sides = listOf(team(3), team(3))
        val match = create(MatchFormat.RANKED_3V3, sides)

        assertEquals(sides, match.rosters)
        assertEquals(sides.flatten(), match.participants)
        assertEquals(0, match.sideOf(sides[0][2]))
        assertEquals(1, match.sideOf(sides[1][0]))
        assertEquals(sides[0].drop(1), match.teammatesOf(sides[0][0]))
        assertEquals(sides[1], match.opponentsOf(sides[0][0]))
        // the two-side view older queries read still points somewhere sensible
        assertEquals(sides[0].first(), match.playerA)
        assertEquals(sides[1].first(), match.playerB)
    }

    @Test
    fun `creating a match with a duplicated player is refused`() {
        val shared = team(1).single()
        val other = team(3)
        val exception = runCatching {
            create(MatchFormat.RANKED_2V2, listOf(listOf(shared, other[0]), listOf(shared, other[1])))
        }.exceptionOrNull()
        assertIs<IllegalArgumentException>(exception)
    }

    @Test
    fun `a team win moves every winner up and every loser down on the mode ladder`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.RANKED_2V2, sides)

        assertIs<MatchService.SettleResult.Settled>(
            service.settle(report(match.id), match.serverToken)
        )

        for (winner in sides[0]) {
            val row = service.modeStatsFor(winner, MatchFormat.RANKED_2V2)
            assertTrue(row.rating > 1000, "winner $winner should have gained rating")
            assertEquals(1, row.wins)
            assertEquals(1, row.currentStreak)
            assertEquals(900, row.playtimeSeconds)
        }
        for (loser in sides[1]) {
            val row = service.modeStatsFor(loser, MatchFormat.RANKED_2V2)
            assertTrue(row.rating < 1000, "loser $loser should have lost rating")
            assertEquals(1, row.losses)
            assertEquals(0, row.currentStreak)
        }
    }

    @Test
    fun `a team match never touches the solo ladder`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.RANKED_2V2, sides)
        service.settle(report(match.id), match.serverToken)

        for (player in sides.flatten()) {
            val solo = service.statsFor(player)
            assertEquals(1000, solo.rating, "the 1v1 ladder must not move on a 2v2 result")
            assertEquals(0, solo.matchesPlayed)
        }
    }

    @Test
    fun `each mode keeps its own ladder`() {
        val roster = team(4)
        val twos = create(MatchFormat.RANKED_2V2, listOf(roster.take(2), roster.drop(2)))
        service.settle(report(twos.id), twos.serverToken)

        val winner = roster.first()
        assertTrue(service.modeStatsFor(winner, MatchFormat.RANKED_2V2).rating > 1000)
        assertEquals(
            1000,
            service.modeStatsFor(winner, MatchFormat.RANKED_3V3).rating,
            "a 2v2 result says nothing about 3v3",
        )
    }

    @Test
    fun `casual team matches record playtime but no rating`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.CASUAL_2V2, sides)

        service.settle(report(match.id, durationSeconds = 600), match.serverToken)

        for (player in sides.flatten()) {
            val row = service.modeStatsFor(player, MatchFormat.CASUAL_2V2)
            assertEquals(1, row.matchesPlayed, "casual play still counts for the mode breakdown")
            assertEquals(600, row.playtimeSeconds)
            assertEquals(1000, row.rating, "an unrated mode has no ladder to move")
        }
    }

    @Test
    fun `a rated format played unrated does not move ratings`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.RANKED_2V2, sides, ranked = false)
        assertFalse(match.rated)

        service.settle(report(match.id), match.serverToken)

        for (player in sides.flatten()) {
            val row = service.modeStatsFor(player, MatchFormat.RANKED_2V2)
            assertEquals(1000, row.rating, "a party practice match must not touch the ladder")
            assertEquals(1, row.matchesPlayed, "but it is still a match that was played")
        }
    }

    @Test
    fun `a free-for-all needs winningTeam to name a winner`() {
        val sides = List(3) { team(1) }
        val match = create(MatchFormat.PARTY_FFA, sides, ranked = false)

        val refused = service.settle(report(match.id), match.serverToken)
        val reason = assertIs<MatchService.SettleResult.InvalidReport>(refused).reason
        assertTrue("winningTeam" in reason, "reason was: $reason")

        val accepted = service.settle(
            report(match.id, MatchOutcome.TEAM_A_WIN, winningTeam = 2),
            match.serverToken,
        )
        val settled = assertIs<MatchService.SettleResult.Settled>(accepted).match
        assertEquals(2, settled.winningTeam)
        assertTrue(settled.didWin(sides[2].single()))
        assertFalse(settled.didWin(sides[0].single()))
    }

    @Test
    fun `a winningTeam outside the match is refused`() {
        val match = create()
        val result = service.settle(report(match.id, winningTeam = 7), match.serverToken)
        assertIs<MatchService.SettleResult.InvalidReport>(result)
        assertEquals(MatchStatus.PENDING, matches.get(match.id)!!.status)
    }

    @Test
    fun `a winningTeam contradicting the outcome is refused`() {
        val match = create()
        val result = service.settle(
            report(match.id, MatchOutcome.TEAM_A_WIN, winningTeam = 1),
            match.serverToken,
        )
        assertIs<MatchService.SettleResult.InvalidReport>(result)
    }

    @Test
    fun `a draw may not carry a winning team`() {
        val match = create()
        val result = service.settle(
            report(match.id, MatchOutcome.DRAW, winningTeam = 0),
            match.serverToken,
        )
        assertIs<MatchService.SettleResult.InvalidReport>(result)
    }

    @Test
    fun `team scores must cover every side`() {
        val match = create(MatchFormat.RANKED_3V3, listOf(team(3), team(3)))
        val result = service.settle(
            report(match.id, teamScores = listOf(5, 3, 1)),
            match.serverToken,
        )
        assertIs<MatchService.SettleResult.InvalidReport>(result)
    }

    @Test
    fun `a forfeit must name someone who actually played`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.RANKED_2V2, sides)

        val outsider = service.settle(
            report(match.id, forfeitedBy = UUID.randomUUID().toString()),
            match.serverToken,
        )
        assertIs<MatchService.SettleResult.InvalidReport>(outsider)

        // a non-captain member of the losing side is a valid forfeiter
        val forfeiter = sides[1][1]
        val ok = service.settle(
            report(match.id, forfeitedBy = forfeiter.toString()),
            match.serverToken,
        )
        assertIs<MatchService.SettleResult.Settled>(ok)
        assertEquals(1, service.modeStatsFor(forfeiter, MatchFormat.RANKED_2V2).forfeits)
    }

    @Test
    fun `a draw counts for both sides and breaks no streak`() {
        val sides = listOf(team(2), team(2))
        val first = create(MatchFormat.RANKED_2V2, sides)
        service.settle(report(first.id), first.serverToken)

        val second = create(MatchFormat.RANKED_2V2, sides)
        service.settle(report(second.id, MatchOutcome.DRAW), second.serverToken)

        val winner = sides[0].first()
        val row = service.modeStatsFor(winner, MatchFormat.RANKED_2V2)
        assertEquals(1, row.draws)
        assertEquals(2, row.matchesPlayed)
        assertEquals(0, row.currentStreak, "a draw is not a win")
        assertEquals(1, row.bestStreak, "but the streak they had is remembered")
    }

    @Test
    fun `a voided team match writes nothing`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.RANKED_2V2, sides)

        service.settle(report(match.id, MatchOutcome.VOID), match.serverToken)

        for (player in sides.flatten()) {
            assertEquals(0, service.modeStatsFor(player, MatchFormat.RANKED_2V2).matchesPlayed)
        }
        assertEquals(MatchStatus.VOIDED, matches.get(match.id)!!.status)
    }

    @Test
    fun `a second report for the same team match is rejected`() {
        val match = create()
        assertIs<MatchService.SettleResult.Settled>(service.settle(report(match.id), match.serverToken))
        assertIs<MatchService.SettleResult.AlreadySettled>(
            service.settle(report(match.id), match.serverToken)
        )
    }

    @Test
    fun `a bad server token settles nothing`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.RANKED_2V2, sides)

        assertIs<MatchService.SettleResult.BadToken>(service.settle(report(match.id), "wrong"))
        assertEquals(0, service.modeStatsFor(sides[0].first(), MatchFormat.RANKED_2V2).matchesPlayed)
    }

    @Test
    fun `streaks build across matches and reset on a loss`() {
        val sides = listOf(team(2), team(2))
        repeat(3) {
            val match = create(MatchFormat.RANKED_2V2, sides)
            service.settle(report(match.id), match.serverToken)
        }
        val winner = sides[0].first()
        assertEquals(3, service.modeStatsFor(winner, MatchFormat.RANKED_2V2).currentStreak)
        assertEquals(3, service.winStreakOf(winner))

        val loss = create(MatchFormat.RANKED_2V2, sides)
        service.settle(report(loss.id, MatchOutcome.TEAM_B_WIN), loss.serverToken)

        assertEquals(0, service.modeStatsFor(winner, MatchFormat.RANKED_2V2).currentStreak)
        assertEquals(3, service.modeStatsFor(winner, MatchFormat.RANKED_2V2).bestStreak)
        assertEquals(0, service.winStreakOf(winner))
        assertEquals(3, service.bestStreakOf(winner))
    }

    @Test
    fun `carrying a weaker team is worth more than beating an equal one`() {
        // Both matches are won by a 1400-rated player; only the opposition
        // differs, and the side's mean is what the rating engine reads.
        val carried = team(2)
        val strongPair = team(2)
        modeStats.upsert(
            service.modeStatsFor(carried[0], MatchFormat.RANKED_2V2).copy(rating = 1400)
        )
        modeStats.upsert(
            service.modeStatsFor(carried[1], MatchFormat.RANKED_2V2).copy(rating = 600)
        )
        for (player in strongPair) {
            modeStats.upsert(service.modeStatsFor(player, MatchFormat.RANKED_2V2).copy(rating = 1400))
        }

        val underdog = create(MatchFormat.RANKED_2V2, listOf(carried, strongPair))
        service.settle(report(underdog.id), underdog.serverToken)
        val carryGain = service.modeStatsFor(carried[0], MatchFormat.RANKED_2V2).rating - 1400

        val even = listOf(team(2), team(2))
        val evenMatch = create(MatchFormat.RANKED_2V2, even)
        service.settle(report(evenMatch.id), evenMatch.serverToken)
        val evenGain = service.modeStatsFor(even[0].first(), MatchFormat.RANKED_2V2).rating - 1000

        assertTrue(carryGain > evenGain, "carrying gained $carryGain, an even win gained $evenGain")
    }

    @Test
    fun `history and head-to-head see every player of a team match`() {
        val sides = listOf(team(2), team(2))
        val match = create(MatchFormat.RANKED_2V2, sides)
        service.settle(report(match.id), match.serverToken)

        val benchWarmer = sides[0][1]
        assertEquals(1, matches.historyFor(benchWarmer, seasons.currentSeason, 10).size)
        assertEquals(
            1,
            matches.between(benchWarmer, sides[1][1], seasons.currentSeason, 10).size,
            "two players on opposite sides met, even though neither is a captain",
        )
        assertTrue(
            matches.between(benchWarmer, sides[0][0], seasons.currentSeason, 10).isEmpty(),
            "teammates did not play against each other",
        )
    }

    @Test
    fun `per-side seeds are generated only when the sides are apart`() {
        val shared = create()
        assertTrue(shared.settings.perTeamWorldSeeds.isEmpty())
        assertTrue(shared.settings.perTeamCardSeeds.isEmpty())

        val split = service.createTeamMatch(
            MatchService.TeamMatchRequest(
                format = MatchFormat.PARTY_TEAMS,
                teams = listOf(team(1), team(1)),
                sharedWorld = false,
                sharedSeed = false,
                ranked = false,
            )
        )
        assertEquals(2, split.settings.perTeamWorldSeeds.size)
        assertEquals(2, split.settings.perTeamCardSeeds.size)
        assertNotEquals(split.settings.perTeamWorldSeeds[0], split.settings.perTeamWorldSeeds[1])
        assertNull(split.winningTeam)
    }
}
