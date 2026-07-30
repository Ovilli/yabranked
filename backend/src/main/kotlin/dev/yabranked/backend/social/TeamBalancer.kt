package dev.yabranked.backend.social

import java.util.UUID
import kotlin.math.abs
import kotlin.random.Random

/**
 * Splitting a roster into sides.
 *
 * Used by both halves of the feature: a party leader who picked "balanced"
 * teams, and the XvX open queue, which builds two sides out of soloists who
 * have never met. Keeping one implementation means the two cannot drift into
 * disagreeing about what "fair" is.
 */
object TeamBalancer {
    data class Rated(val uuid: UUID, val rating: Int)

    /**
     * Split [players] into [teamCount] sides of equal size, minimising the
     * spread of side means.
     *
     * Greedy snake draft: sort by rating, then deal top-down and bottom-up in
     * alternating passes. Exhaustive search would be exact but is factorial in
     * the roster size, and the snake is within a few rating points of optimal
     * for the sizes that exist here (up to 4v4).
     *
     * Requires `players.size % teamCount == 0`; callers check that first,
     * because a leftover player is a matchmaking bug, not something to round
     * away silently.
     */
    fun balanced(players: List<Rated>, teamCount: Int = 2): List<List<UUID>> {
        require(teamCount >= 2) { "need at least two sides" }
        require(players.size % teamCount == 0) {
            "cannot split ${players.size} players into $teamCount equal sides"
        }
        val perTeam = players.size / teamCount
        val sorted = players.sortedByDescending { it.rating }
        val teams = List(teamCount) { mutableListOf<UUID>() }
        var index = 0
        var round = 0
        while (index < sorted.size) {
            // snake: left-to-right, then right-to-left, so the second-best
            // player lands opposite the best rather than beside them
            val order = if (round % 2 == 0) teams.indices.toList() else teams.indices.reversed().toList()
            for (team in order) {
                if (index >= sorted.size) break
                if (teams[team].size >= perTeam) continue
                teams[team] += sorted[index++].uuid
            }
            round++
        }
        return teams.map { it.toList() }
    }

    /** Even split, ignoring rating. The party leader's "random" option. */
    fun random(players: List<UUID>, teamCount: Int = 2, random: Random = Random.Default): List<List<UUID>> {
        require(players.size % teamCount == 0) {
            "cannot split ${players.size} players into $teamCount equal sides"
        }
        return players.shuffled(random).chunked(players.size / teamCount)
    }

    /** One side per player: the party free-for-all shape. */
    fun freeForAll(players: List<UUID>): List<List<UUID>> = players.map { listOf(it) }

    /** Mean rating of a side, 0 for an empty one. */
    fun averageRating(team: List<UUID>, ratings: Map<UUID, Int>): Int =
        if (team.isEmpty()) 0 else team.sumOf { ratings[it] ?: 0 } / team.size

    /** Median rating of a group — what party-vs-party matching compares. */
    fun medianRating(ratings: Collection<Int>): Int {
        if (ratings.isEmpty()) return 0
        val sorted = ratings.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** Largest gap between any two side means; the fairness number to minimise. */
    fun spread(teams: List<List<UUID>>, ratings: Map<UUID, Int>): Int {
        if (teams.isEmpty()) return 0
        val means = teams.map { averageRating(it, ratings) }
        return abs((means.maxOrNull() ?: 0) - (means.minOrNull() ?: 0))
    }
}
