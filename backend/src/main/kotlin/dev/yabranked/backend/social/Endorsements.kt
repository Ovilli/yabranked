package dev.yabranked.backend.social

import dev.yabranked.backend.store.EndorsementRecord
import dev.yabranked.backend.store.EndorsementStore
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.proto.EndorsementCategory
import dev.yabranked.proto.EndorsementSummary
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Teammate endorsements.
 *
 * Only reachable from modes with teammates ([dev.yabranked.proto.MatchFormat.endorsable]),
 * only for players who were actually on your side of an actually-decided match,
 * only once per teammate per match, and only inside a window after the match
 * ends. Those four rules are what keep an endorsement level a signal rather
 * than a measure of how many matches a friend group played.
 */
class EndorsementService(
    private val endorsements: EndorsementStore,
    private val matches: MatchStore,
    private val clock: Clock = Clock.systemUTC(),
    /**
     * How long after a match teammates may still endorse. Long enough to leave
     * the server, read the scoreboard and open the menu; short enough that the
     * prompt is about *this* match.
     */
    val window: Duration = Duration.ofHours(24),
) {
    sealed interface Result {
        data class Ok(val awarded: Int) : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Cumulative endorsements needed to reach each level. Front-loaded so the
     * first levels arrive while a player still cares, then widening so the top
     * of the scale stays rare.
     */
    private val thresholds = listOf(0, 5, 15, 35, 70, 125, 200, 320, 500, 750)

    val maxLevel: Int get() = thresholds.size

    fun levelFor(total: Int): Int =
        thresholds.indexOfLast { total >= it }.coerceAtLeast(0) + 1

    /** 0..1 through the current level; 1 once the top level is reached. */
    fun progressFor(total: Int): Float {
        val level = levelFor(total)
        if (level >= maxLevel) return 1f
        val floor = thresholds[level - 1]
        val ceiling = thresholds[level]
        if (ceiling <= floor) return 1f
        return ((total - floor).toFloat() / (ceiling - floor)).coerceIn(0f, 1f)
    }

    fun summaryFor(uuid: UUID): EndorsementSummary {
        val total = endorsements.totalFor(uuid)
        return EndorsementSummary(
            level = levelFor(total),
            total = total,
            progress = progressFor(total),
            categories = endorsements.countsFor(uuid).mapKeys { it.key.name.lowercase() },
        )
    }

    fun summariesFor(uuids: Collection<UUID>): Map<UUID, EndorsementSummary> {
        val totals = endorsements.totalsFor(uuids)
        return uuids.associateWith { uuid ->
            val total = totals[uuid] ?: 0
            EndorsementSummary(level = levelFor(total), total = total, progress = progressFor(total))
        }
    }

    /**
     * Teammates [player] may still endorse for [matchId], or null when the
     * match is not theirs, not endorsable, or the window has closed.
     */
    fun promptFor(player: UUID, matchId: UUID): List<UUID>? {
        val match = matches.get(matchId) ?: return null
        if (!match.format.endorsable) return null
        if (!match.isDecided) return null
        val completedAt = match.completedAt ?: return null
        if (Duration.between(completedAt, clock.instant()) > window) return null
        if (match.sideOf(player) == null) return null
        if (endorsements.hasEndorsed(matchId, player)) return null
        return match.teammatesOf(player).takeIf { it.isNotEmpty() }
    }

    /** True when the client should offer the endorse prompt for this match. */
    fun canEndorse(player: UUID, matchId: UUID): Boolean = !promptFor(player, matchId).isNullOrEmpty()

    fun endorse(
        player: UUID,
        matchId: UUID,
        targets: Collection<UUID>,
        category: EndorsementCategory,
    ): Result {
        val match = matches.get(matchId) ?: return Result.Rejected("unknown match")
        if (!match.format.endorsable) return Result.Rejected("this mode has no teammates to endorse")
        if (!match.isDecided) return Result.Rejected("that match had no result")
        val completedAt = match.completedAt ?: return Result.Rejected("that match is not finished")
        if (Duration.between(completedAt, clock.instant()) > window) {
            return Result.Rejected("the endorsement window for this match has closed")
        }
        if (match.sideOf(player) == null) return Result.Rejected("you were not in that match")

        val teammates = match.teammatesOf(player).toSet()
        val wanted = targets.toSet()
        if (wanted.isEmpty()) return Result.Rejected("nobody selected")
        // Endorsing yourself, an opponent, or someone who was never there is a
        // client bug or an attack; either way nothing is written.
        val invalid = wanted - teammates
        if (invalid.isNotEmpty()) return Result.Rejected("you can only endorse your own teammates")

        val now = clock.instant()
        var awarded = 0
        for (target in wanted) {
            val inserted = endorsements.insert(
                EndorsementRecord(
                    matchId = matchId,
                    from = player,
                    to = target,
                    category = category,
                    createdAt = now,
                )
            )
            if (inserted) awarded++
        }
        if (awarded == 0) return Result.Rejected("already endorsed")
        return Result.Ok(awarded)
    }
}
