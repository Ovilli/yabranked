package dev.yabranked.backend.match

import dev.yabranked.backend.achievement.AchievementContext
import dev.yabranked.backend.achievement.AchievementDef
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.RatingState
import dev.yabranked.backend.rating.RatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.AchievementStore
import dev.yabranked.backend.store.InMemoryAchievementStore
import dev.yabranked.backend.store.LockingTransactionRunner
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.SeasonStats
import dev.yabranked.backend.store.TransactionRunner
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.MatchSettings
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * Creates match records from queue matches and settles reported results.
 * Provisioning the actual game server is the orchestrator's job — it
 * subscribes via [onMatchCreated] / [onMatchSettled].
 */
class MatchService(
    private val players: PlayerStore,
    private val matches: MatchStore,
    private val rating: RatingSystem,
    private val seasons: SeasonService,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val achievements: AchievementStore = InMemoryAchievementStore(),
    /**
     * Makes the settle path atomic. Every write in [settle] — both players'
     * stats, the match row and the achievement unlocks — commits together or
     * not at all, and concurrent settles touching the same player serialize
     * instead of clobbering each other's rating.
     */
    private val transactions: TransactionRunner = LockingTransactionRunner(),
) {
    private val listeners = mutableListOf<(MatchRecord) -> Unit>()
    private val settledListeners = mutableListOf<(MatchRecord) -> Unit>()

    fun onMatchCreated(listener: (MatchRecord) -> Unit) {
        listeners += listener
    }

    fun onMatchSettled(listener: (MatchRecord) -> Unit) {
        settledListeners += listener
    }

    fun getOrCreatePlayer(uuid: UUID, name: String): PlayerRecord {
        val existing = players.getPlayer(uuid)
        val record = when {
            existing == null -> PlayerRecord(uuid = uuid, name = name, createdAt = clock.instant())
            existing.name != name -> existing.copy(name = name) // MC accounts can rename
            else -> existing
        }
        if (record !== existing) players.upsertPlayer(record)
        return record
    }

    /**
     * Current-season stats, defaulting to the initial rating for a player with
     * no row yet. Purely a read: persisting that placeholder here meant every
     * profile view, leaderboard render and queue join wrote to the database.
     * Only [settle] creates the row.
     */
    fun statsFor(uuid: UUID): SeasonStats {
        val season = seasons.currentSeason
        return players.getStats(uuid, season) ?: freshStats(uuid, season)
    }

    /**
     * Matches before a player is ranked. Read from the rating system rather
     * than kept alongside it: the two used to declare 5 independently, so
     * drifting one would have had the client counting down placements the
     * K-factor had already stopped applying.
     */
    val placementMatches: Int get() = rating.placementMatches

    /** The ladder's rating rules, for the season rollover and the decay sweep. */
    val ratingSystem: RatingSystem get() = rating

    fun placementMatchesRemaining(stats: SeasonStats): Int =
        (placementMatches - stats.matchesPlayed).coerceAtLeast(0)

    /** Orchestrator: record where the provisioned server for this match lives. */
    fun setServerAddress(matchId: UUID, address: String) {
        transactions.transaction {
            val match = matches.getForUpdate(matchId) ?: error("unknown match $matchId")
            matches.update(match.copy(serverAddress = address))
        }
    }

    sealed interface ReadyResult {
        data object Ok : ReadyResult
        data object UnknownMatch : ReadyResult
        data object BadToken : ReadyResult
    }

    /** Agent: the match server is configured and waiting for its players. */
    fun markReady(matchId: String, serverToken: String): ReadyResult {
        val id = runCatching { UUID.fromString(matchId) }.getOrNull()
            ?: return ReadyResult.UnknownMatch
        return transactions.transaction {
            val match = matches.getForUpdate(id) ?: return@transaction ReadyResult.UnknownMatch
            if (!java.security.MessageDigest.isEqual(
                    match.serverToken.toByteArray(),
                    serverToken.toByteArray()
                )
            ) return@transaction ReadyResult.BadToken

            if (match.status == MatchStatus.PENDING) {
                matches.update(match.copy(status = MatchStatus.ACTIVE))
            }
            ReadyResult.Ok
        }
    }

    /**
     * Void a match without a server token — internal use only (e.g. the
     * orchestrator reaping a match whose server never became ready, or startup
     * reconciliation clearing matches orphaned by a restart).
     */
    fun voidMatch(matchId: UUID) {
        val voided = transactions.transaction {
            val match = matches.getForUpdate(matchId) ?: return@transaction null
            if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.VOIDED) {
                return@transaction null
            }
            match.copy(
                status = MatchStatus.VOIDED,
                outcome = MatchOutcome.VOID,
                completedAt = clock.instant(),
            ).also(matches::update)
        } ?: return
        // listeners (container teardown) run outside the transaction: they do
        // slow I/O and must not hold row locks or be rolled back with it.
        settledListeners.forEach { it(voided) }
    }

    fun createMatch(queueMatch: QueueMatch, format: MatchFormat): MatchRecord {
        val record = transactions.transaction { buildMatch(queueMatch, format) }
        listeners.forEach { it(record) }
        return record
    }

    private fun buildMatch(queueMatch: QueueMatch, format: MatchFormat): MatchRecord {
        val statsA = statsFor(queueMatch.playerA.uuid)
        val statsB = statsFor(queueMatch.playerB.uuid)

        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        val record = MatchRecord(
            id = UUID.randomUUID(),
            season = seasons.currentSeason,
            format = format,
            settings = MatchSettings(
                format = format,
                worldSeed = random.nextLong(),
                cardSeed = random.nextLong(),
                timeLimitSeconds = format.rules.timeLimitMinutes * 60L,
            ),
            playerA = statsA.uuid,
            playerB = statsB.uuid,
            status = MatchStatus.PENDING,
            serverToken = token,
            outcome = null,
            ratingABefore = statsA.rating,
            ratingBBefore = statsB.rating,
            ratingAAfter = null,
            ratingBAfter = null,
            createdAt = clock.instant(),
            completedAt = null,
        )
        matches.insert(record)
        return record
    }

    sealed interface SettleResult {
        data class Settled(val match: MatchRecord) : SettleResult
        data object UnknownMatch : SettleResult
        data object BadToken : SettleResult
        data object AlreadySettled : SettleResult

        /** The agent reported something impossible; nothing was written. */
        data class InvalidReport(val reason: String) : SettleResult
    }

    /**
     * Apply a result reported by the match server. Idempotence: a second
     * report for the same match is rejected with [SettleResult.AlreadySettled].
     */
    fun settle(report: MatchResultReport, serverToken: String): SettleResult {
        val id = runCatching { UUID.fromString(report.matchId) }.getOrNull()
            ?: return SettleResult.UnknownMatch
        val result = transactions.transaction { settleLocked(id, report, serverToken) }
        // teardown listeners do container I/O — keep them out of the transaction
        if (result is SettleResult.Settled) settledListeners.forEach { it(result.match) }
        return result
    }

    private fun settleLocked(id: UUID, report: MatchResultReport, serverToken: String): SettleResult {
        val match = matches.getForUpdate(id) ?: return SettleResult.UnknownMatch
        if (!java.security.MessageDigest.isEqual(
                match.serverToken.toByteArray(),
                serverToken.toByteArray()
            )
        ) return SettleResult.BadToken
        if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.VOIDED)
            return SettleResult.AlreadySettled
        rejectionReason(match, report)?.let { return SettleResult.InvalidReport(it) }

        // Lock both stats rows in ascending uuid order: two matches sharing a
        // player would otherwise be free to grab them in opposite orders and
        // deadlock.
        val locked = listOf(match.playerA, match.playerB)
            .sorted()
            .associateWith { uuid -> players.getStatsForUpdate(uuid, match.season) ?: freshStats(uuid, match.season) }
        val statsA = locked.getValue(match.playerA)
        val statsB = locked.getValue(match.playerB)

        val update = rating.update(
            playerA = RatingState(statsA.rating, statsA.matchesPlayed),
            playerB = RatingState(statsB.rating, statsB.matchesPlayed),
            outcome = report.outcome,
        )

        val forfeitedBy = report.forfeitedBy?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        // Playing resets the inactivity clock and clears the decay watermark, so
        // a returning player starts a fresh grace period rather than being
        // billed for the idle time they just ended.
        val playedAt = clock.instant()

        // casual formats record the match but never touch the ladder
        if (report.outcome != MatchOutcome.VOID && match.format.ranked) {
            players.upsertStats(
                statsA.copy(
                    lastPlayedAt = playedAt,
                    decayedThrough = null,
                    rating = update.playerA.rating,
                    matchesPlayed = update.playerA.matchesPlayed,
                    wins = statsA.wins + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                    losses = statsA.losses + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                    draws = statsA.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
                    playtimeSeconds = statsA.playtimeSeconds + report.durationSeconds,
                    forfeits = statsA.forfeits + if (forfeitedBy == match.playerA) 1 else 0,
                    peakRating = maxOf(statsA.peakRating, update.playerA.rating),
                )
            )
            players.upsertStats(
                statsB.copy(
                    lastPlayedAt = playedAt,
                    decayedThrough = null,
                    rating = update.playerB.rating,
                    matchesPlayed = update.playerB.matchesPlayed,
                    wins = statsB.wins + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                    losses = statsB.losses + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                    draws = statsB.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
                    playtimeSeconds = statsB.playtimeSeconds + report.durationSeconds,
                    forfeits = statsB.forfeits + if (forfeitedBy == match.playerB) 1 else 0,
                    peakRating = maxOf(statsB.peakRating, update.playerB.rating),
                )
            )
        }

        val settled = match.copy(
            status = if (report.outcome == MatchOutcome.VOID) MatchStatus.VOIDED else MatchStatus.COMPLETED,
            outcome = report.outcome,
            ratingAAfter = update.playerA.rating,
            ratingBAfter = update.playerB.rating,
            durationSeconds = report.durationSeconds,
            teamAScore = report.teamAScore,
            teamBScore = report.teamBScore,
            forfeitedBy = forfeitedBy,
            completedAt = clock.instant(),
        )
        matches.update(settled)

        // Achievements ride on the ladder: evaluate once the settled match is
        // visible to historyFor (needed for the win-streak milestones). Casual
        // and voided matches never touch the ladder, so they never award.
        if (report.outcome != MatchOutcome.VOID && match.format.ranked) {
            evaluateAchievements(match.playerA)
            evaluateAchievements(match.playerB)
        }

        return SettleResult.Settled(settled)
    }

    /**
     * Why this report must not be applied, or null when it is plausible.
     *
     * The per-match token proves the report came from that match's server, not
     * that the server is honest: everything below lands in the match record and
     * [MatchResultReport.durationSeconds] is summed into season playtime, so a
     * compromised or simply buggy agent could otherwise write a negative
     * duration, a score no card can produce, or pin a forfeit on someone who
     * never played the match.
     */
    private fun rejectionReason(match: MatchRecord, report: MatchResultReport): String? {
        if (report.durationSeconds < 0) return "negative duration"
        val maxDuration = match.settings.timeLimitSeconds.coerceAtLeast(0) + MAX_DURATION_OVERRUN_SECONDS
        if (report.durationSeconds > maxDuration) return "duration exceeds ${maxDuration}s"
        for (score in listOf(report.teamAScore, report.teamBScore)) {
            if (score < 0) return "negative score"
            if (score > MAX_SCORE) return "score exceeds $MAX_SCORE"
        }
        val forfeitedBy = report.forfeitedBy ?: return null
        val uuid = runCatching { UUID.fromString(forfeitedBy) }.getOrNull()
            ?: return "forfeitedBy is not a uuid"
        if (uuid != match.playerA && uuid != match.playerB) return "forfeitedBy is not in this match"
        return null
    }

    /** Stats for a player who has no row for that season yet. */
    private fun freshStats(uuid: UUID, season: Int) = SeasonStats(
        uuid = uuid,
        season = season,
        rating = rating.initialRating,
        matchesPlayed = 0,
        wins = 0,
        losses = 0,
        draws = 0,
    )

    /**
     * Consecutive wins from the front of the player's newest-first history.
     * Runs on every settle, so it asks only for decided matches and only for
     * [WIN_STREAK_WINDOW] of them — a streak longer than the largest milestone
     * is indistinguishable from one exactly that long.
     */
    private fun winStreakOf(uuid: UUID): Int {
        var streak = 0
        for (match in matches.recentDecided(uuid, seasons.currentSeason, limit = WIN_STREAK_WINDOW)) {
            val outcome = match.outcome ?: continue
            val isTeamA = match.playerA == uuid
            val won = (isTeamA && outcome == MatchOutcome.TEAM_A_WIN) ||
                (!isTeamA && outcome == MatchOutcome.TEAM_B_WIN)
            if (won) streak++ else break
        }
        return streak
    }

    /** Award every catalog milestone the player now qualifies for (idempotent). */
    private fun evaluateAchievements(uuid: UUID) {
        val seasonStats = statsFor(uuid)
        val context = AchievementContext(
            stats = players.lifetimeStats(uuid),
            winStreak = winStreakOf(uuid),
            placed = placementMatchesRemaining(seasonStats) <= 0,
        )
        val now = clock.instant()
        for (def in AchievementDef.qualifying(context)) {
            achievements.award(uuid, def.id, now)
        }
    }

    companion object {
        /**
         * Decided matches [winStreakOf] looks back over. One more than the
         * longest streak milestone in the catalog, so the window can always
         * show both the streak and the loss that would have broken it.
         */
        const val WIN_STREAK_WINDOW = 11

        /** 90 minutes — generous cap for lockout 1v1; tune with real data. */
        const val DEFAULT_TIME_LIMIT_SECONDS = 90L * 60L

        /**
         * Slack a reported duration may add on top of the match's own time
         * limit: lobby time, the postgame linger and clock skew all land in it.
         */
        const val MAX_DURATION_OVERRUN_SECONDS = 60L * 60L

        /** A bingo card is 5x5, so neither side can claim more than 25 objectives. */
        const val MAX_SCORE = 25

        /**
         * How long a match may stay PENDING before the client gives up waiting
         * for its server. The orchestrator's reaper uses this plus a grace
         * period, so the two can never disagree about when a match is dead.
         */
        const val PROVISION_TIMEOUT_SECONDS = 180L

        /** Grace after [PROVISION_TIMEOUT_SECONDS] before the reaper voids. */
        const val PROVISION_REAP_GRACE_SECONDS = 30L
    }
}
