package dev.yabranked.backend.moderation

import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryReplayStore
import dev.yabranked.backend.store.InMemoryReportStore
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.ReportStatus
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ModerationServiceTest {

    private val reports = InMemoryReportStore()
    private val matches = InMemoryMatchStore()
    private val replays = InMemoryReplayStore()
    private val seasons = SeasonService()
    private val service = ModerationService(reports, matches, replays, seasons)

    private val start: Instant = Instant.parse("2026-01-01T00:00:00Z")

    /** Each match is a minute after the last, so "newest" is never a tie. */
    private var played = 0
    private val now: Instant get() = start.plusSeconds(60L * played)

    /** A finished match with the given sides; a 1v1 leaves [MatchRecord.teams] empty. */
    private fun match(sideA: List<UUID>, sideB: List<UUID>): MatchRecord {
        played++
        val now = this.now
        val record = MatchRecord(
            id = UUID.randomUUID(),
            season = seasons.currentSeason,
            format = if (sideA.size == 1) MatchFormat.LOCKOUT_1V1 else MatchFormat.RANKED_2V2,
            settings = MatchSettings(MatchFormat.LOCKOUT_1V1, 1L, 2L, 600L),
            playerA = sideA.first(),
            playerB = sideB.first(),
            status = MatchStatus.COMPLETED,
            serverToken = "token",
            outcome = MatchOutcome.TEAM_A_WIN,
            ratingABefore = 1000,
            ratingBBefore = 1000,
            ratingAAfter = 1040,
            ratingBAfter = 960,
            createdAt = now,
            completedAt = now,
            teams = if (sideA.size == 1) emptyList() else listOf(sideA, sideB),
        )
        matches.insert(record)
        replays.ensure(record.id, now, now.plusSeconds(86_400), underReview = false)
        return record
    }

    private fun underReview(matchId: UUID): Boolean =
        replays.get(matchId)?.underReview ?: error("no replay row for $matchId")

    @Test
    fun `filing a report holds the recording and resolving it lets go`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val played = match(listOf(a), listOf(b))

        val filed = service.file(a, played.id, accused = null, reason = "cheating")
        assertIs<FileResult.Filed>(filed)
        assertEquals(b, filed.report.accused)
        assertEquals(ReportStatus.OPEN, filed.report.status)
        assertTrue(underReview(played.id), "a fresh report must pin the recording")

        val resolved = service.resolve(filed.report.id, ReportStatus.DISMISSED, "ovilli", "no evidence")
        assertEquals(ReportStatus.DISMISSED, resolved?.status)
        assertEquals("ovilli", resolved?.resolvedBy)
        assertEquals("no evidence", resolved?.resolutionNote)
        assertTrue(resolved?.resolvedAt != null, "a decided report must record when")
        assertFalse(
            underReview(played.id),
            "the retention hold had no event that could lift it before resolution existed",
        )
    }

    @Test
    fun `claiming a report keeps the hold and records no decision time`() {
        val a = UUID.randomUUID()
        val played = match(listOf(a), listOf(UUID.randomUUID()))
        val filed = service.file(a, played.id, null, "cheating") as FileResult.Filed

        val claimed = service.resolve(filed.report.id, ReportStatus.REVIEWING, "ovilli", null)

        assertEquals(ReportStatus.REVIEWING, claimed?.status)
        assertNull(claimed?.resolvedAt, "claiming a report is not deciding it")
        assertTrue(underReview(played.id), "an unfinished review must still hold the recording")
    }

    @Test
    fun `the hold survives until every report about the match is decided`() {
        // Two players reporting the same opponent produce two rows, and the
        // recording is the evidence for both. Releasing on the first decision
        // would delete it out from under the second.
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val x = UUID.randomUUID()
        val y = UUID.randomUUID()
        val played = match(listOf(a, b), listOf(x, y))

        val first = service.file(a, played.id, x, "cheating") as FileResult.Filed
        val second = service.file(b, played.id, x, "cheating") as FileResult.Filed

        service.resolve(first.report.id, ReportStatus.ACTIONED, "ovilli", null)
        assertTrue(underReview(played.id), "one report left open must keep the recording")

        service.resolve(second.report.id, ReportStatus.ACTIONED, "ovilli", null)
        assertFalse(underReview(played.id))
    }

    @Test
    fun `resolving an unknown report is not a silent success`() {
        assertNull(service.resolve(UUID.randomUUID(), ReportStatus.DISMISSED, "ovilli", null))
    }

    @Test
    fun `every opponent in a team match can be reported separately`() {
        // The rule is one report per accused per match, not one per match: a
        // 4v4 has four opponents and they do not misbehave as a unit.
        val a = UUID.randomUUID()
        val x = UUID.randomUUID()
        val y = UUID.randomUUID()
        val played = match(listOf(a, UUID.randomUUID()), listOf(x, y))

        assertIs<FileResult.Filed>(service.file(a, played.id, x, "cheating"))
        assertIs<FileResult.Filed>(service.file(a, played.id, y, "also cheating"))
        assertIs<FileResult.AlreadyReported>(service.file(a, played.id, x, "again"))
    }

    @Test
    fun `naming a teammate or a stranger is refused rather than recorded`() {
        val a = UUID.randomUUID()
        val mate = UUID.randomUUID()
        val played = match(listOf(a, mate), listOf(UUID.randomUUID(), UUID.randomUUID()))

        assertIs<FileResult.NotOnOtherSide>(service.file(a, played.id, mate, "griefing"))
        assertIs<FileResult.NotOnOtherSide>(service.file(a, played.id, UUID.randomUUID(), "hacking"))
    }

    @Test
    fun `a report from a profile finds the newest shared match`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        match(listOf(a), listOf(b))
        val newest = match(listOf(a), listOf(b))

        val filed = service.file(a, matchId = null, accused = b, reason = "hacking")

        assertIs<FileResult.Filed>(filed)
        assertEquals(newest.id, filed.report.matchId)
    }

    @Test
    fun `a report identifying nothing, and one from a non-participant, are told apart`() {
        val a = UUID.randomUUID()
        val played = match(listOf(a), listOf(UUID.randomUUID()))

        assertIs<FileResult.NothingIdentified>(service.file(a, null, null, "hacking"))
        assertIs<FileResult.NoSuchMatch>(service.file(UUID.randomUUID(), played.id, null, "hacking"))
        assertIs<FileResult.NoSuchMatch>(service.file(a, UUID.randomUUID(), null, "hacking"))
    }

    @Test
    fun `the queue can be filtered to what still needs a decision`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val first = service.file(a, match(listOf(a), listOf(b)).id, null, "one") as FileResult.Filed
        service.file(a, match(listOf(a), listOf(b)).id, null, "two")

        service.resolve(first.report.id, ReportStatus.DISMISSED, "ovilli", null)

        assertEquals(2, service.list(50).size)
        assertEquals(1, service.list(50, ReportStatus.OPEN).size)
        assertEquals(1, service.list(50, ReportStatus.DISMISSED).size)
    }

    @Test
    fun `the listing counts every report against the accused, not just this one`() {
        // Ordered by recency, the queue surfaces whoever was reported last
        // rather than whoever is reported constantly.
        val b = UUID.randomUUID()
        repeat(3) {
            val a = UUID.randomUUID()
            service.file(a, match(listOf(a), listOf(b)).id, null, "cheating")
        }

        assertTrue(service.list(50).all { it.totalAgainstAccused == 3 })
    }

    @Test
    fun `a note or moderator omitted on a later transition keeps what was recorded`() {
        val a = UUID.randomUUID()
        val played = match(listOf(a), listOf(UUID.randomUUID()))
        val filed = service.file(a, played.id, null, "cheating") as FileResult.Filed

        service.resolve(filed.report.id, ReportStatus.REVIEWING, "ovilli", "looking now")
        val decided = service.resolve(filed.report.id, ReportStatus.ACTIONED, null, null)

        assertEquals("ovilli", decided?.resolvedBy)
        assertEquals("looking now", decided?.resolutionNote)
    }

    @Test
    fun `a blank moderator name is not a moderator name`() {
        val a = UUID.randomUUID()
        val played = match(listOf(a), listOf(UUID.randomUUID()))
        val filed = service.file(a, played.id, null, "cheating") as FileResult.Filed

        val decided = service.resolve(filed.report.id, ReportStatus.DISMISSED, "   ", "")

        assertNull(decided?.resolvedBy)
        assertNull(decided?.resolutionNote)
    }
}
