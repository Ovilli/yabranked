package dev.yabranked.backend.moderation

import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.ReplayStore
import dev.yabranked.backend.store.ReportRecord
import dev.yabranked.backend.store.ReportStatus
import dev.yabranked.backend.store.ReportStore
import java.time.Clock
import java.util.UUID

/** How much of a report's text is kept. Longer than this is an essay, not a report. */
private const val MAX_REASON_LENGTH = 500

/** Same reasoning, for the moderator's own note and name. */
private const val MAX_NOTE_LENGTH = 1000
private const val MAX_MODERATOR_LENGTH = 64

/** Outcome of filing a report; the route only maps these onto status codes. */
sealed interface FileResult {
    data class Filed(val report: ReportRecord) : FileResult

    /** Neither a match id nor an accused player was named. */
    data object NothingIdentified : FileResult

    /** No such match, or the reporter was not in it. Same answer for both, deliberately. */
    data object NoSuchMatch : FileResult

    /** The named accused was on the reporter's own side, or not in the match at all. */
    data object NotOnOtherSide : FileResult

    /** This reporter has already reported this player for this match. */
    data object AlreadyReported : FileResult
}

/** One report plus the standing of the account it accuses. */
data class ReportView(
    val report: ReportRecord,
    /** Every report ever filed against [ReportRecord.accused], this one included. */
    val totalAgainstAccused: Int,
)

/**
 * Filing and judging player reports.
 *
 * The rules live here rather than in the route for the same reason the social
 * services do: `POST /v1/reports` decides who the accused *is* (a report from a
 * profile names no match, one from the post-match screen names no accused) and
 * that is a rule, not parsing.
 *
 * The part that is new is the other half. A report used to be write-only —
 * filed, listed, never touched again — which meant the retention hold it puts
 * on the match recording had no event that could lift it, and a moderator
 * looking at the queue could not tell an accusation nobody had read from one
 * that had already been judged and rejected.
 */
class ModerationService(
    private val reports: ReportStore,
    private val matches: MatchStore,
    private val replays: ReplayStore,
    private val seasons: SeasonService,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * Files a report by [reporter]. Exactly one of [matchId] and [accused] is
     * enough: with a match the accused is that match's opponent, and with an
     * accused the match is the newest one the two shared.
     */
    fun file(reporter: UUID, matchId: UUID?, accused: UUID?, reason: String): FileResult {
        if (matchId == null && accused == null) return FileResult.NothingIdentified

        val match = if (matchId != null) {
            matches.get(matchId)
        } else {
            // Newest shared match, across every mode: the thing being reported
            // is behaviour, and it does not stop being reportable because it
            // happened in a casual game.
            matches.between(reporter, accused!!, seasons.currentSeason, limit = 1).firstOrNull()
        }
        // Membership through the roster, not playerA/playerB: those are only
        // each side's first player once teams exist, so four of a 3v3's six
        // players could not file a report at all.
        if (match == null || match.sideOf(reporter) == null) return FileResult.NoSuchMatch

        val opponents = match.opponentsOf(reporter)
        val target = when {
            accused == null -> opponents.firstOrNull() ?: return FileResult.NoSuchMatch
            accused in opponents -> accused
            else -> return FileResult.NotOnOtherSide
        }

        if (reports.existsFor(match.id, reporter, target)) return FileResult.AlreadyReported

        val record = ReportRecord(
            id = UUID.randomUUID(),
            matchId = match.id,
            reporter = reporter,
            accused = target,
            reason = reason.take(MAX_REASON_LENGTH),
            createdAt = clock.instant(),
            status = ReportStatus.OPEN,
        )
        reports.insert(record)
        // A reported match's recording is what a moderator will actually judge
        // the accusation on, so it stops being subject to the retention sweep
        // the moment the report exists — and stays that way whatever the
        // players do with their own copies, until [resolve] decides otherwise.
        replays.setUnderReview(match.id, true)
        return FileResult.Filed(record)
    }

    /** The moderation queue. [status] null lists every state. */
    fun list(limit: Int, status: ReportStatus? = null): List<ReportView> {
        val rows = reports.list(limit, status)
        val counts = reports.countsAgainst(rows.map { it.accused }.toSet())
        return rows.map { ReportView(it, counts[it.accused] ?: 1) }
    }

    /**
     * Records a moderator's decision, and releases the recording's retention
     * hold once nothing about that match is still open.
     *
     * Deliberately per match rather than per report: two players reporting the
     * same opponent produce two rows, and the recording must survive until both
     * have been looked at. Returns null if [id] names no report.
     */
    fun resolve(id: UUID, status: ReportStatus, moderator: String?, note: String?): ReportRecord? {
        val updated = reports.resolve(
            id = id,
            status = status,
            moderator = moderator?.trim()?.take(MAX_MODERATOR_LENGTH)?.ifBlank { null },
            note = note?.trim()?.take(MAX_NOTE_LENGTH)?.ifBlank { null },
            at = clock.instant(),
        ) ?: return null

        val stillOpen = reports.forMatch(updated.matchId).any { it.status.open }
        replays.setUnderReview(updated.matchId, stillOpen)
        return updated
    }

    /** Every report filed about [matchId], oldest first. */
    fun forMatch(matchId: UUID): List<ReportRecord> = reports.forMatch(matchId)
}
