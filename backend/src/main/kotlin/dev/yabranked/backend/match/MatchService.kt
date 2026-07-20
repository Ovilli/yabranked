package dev.yabranked.backend.match

import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.RatingState
import dev.yabranked.backend.rating.RatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.SeasonStats
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
    private val placementMatches: Int = 5,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
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

    /** Current-season stats, created at the initial rating on first touch. */
    fun statsFor(uuid: UUID): SeasonStats {
        val season = seasons.currentSeason
        return players.getStats(uuid, season) ?: SeasonStats(
            uuid = uuid,
            season = season,
            rating = rating.initialRating,
            matchesPlayed = 0,
            wins = 0,
            losses = 0,
            draws = 0,
        ).also(players::upsertStats)
    }

    fun placementMatchesRemaining(stats: SeasonStats): Int =
        (placementMatches - stats.matchesPlayed).coerceAtLeast(0)

    /** Orchestrator: record where the provisioned server for this match lives. */
    fun setServerAddress(matchId: UUID, address: String) {
        val match = matches.get(matchId) ?: error("unknown match $matchId")
        matches.update(match.copy(serverAddress = address))
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
        val match = matches.get(id) ?: return ReadyResult.UnknownMatch
        if (!java.security.MessageDigest.isEqual(
                match.serverToken.toByteArray(),
                serverToken.toByteArray()
            )
        ) return ReadyResult.BadToken

        if (match.status == MatchStatus.PENDING) {
            matches.update(match.copy(status = MatchStatus.ACTIVE))
        }
        return ReadyResult.Ok
    }

    /**
     * Void a match without a server token — internal use only (e.g. the
     * orchestrator reaping a match whose server never became ready).
     */
    fun voidMatch(matchId: UUID) {
        val match = matches.get(matchId) ?: return
        if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.VOIDED) return
        val voided = match.copy(
            status = MatchStatus.VOIDED,
            outcome = MatchOutcome.VOID,
            completedAt = clock.instant(),
        )
        matches.update(voided)
        settledListeners.forEach { it(voided) }
    }

    fun createMatch(queueMatch: QueueMatch, format: MatchFormat): MatchRecord {
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
                timeLimitSeconds = DEFAULT_TIME_LIMIT_SECONDS,
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
        listeners.forEach { it(record) }
        return record
    }

    sealed interface SettleResult {
        data class Settled(val match: MatchRecord) : SettleResult
        data object UnknownMatch : SettleResult
        data object BadToken : SettleResult
        data object AlreadySettled : SettleResult
    }

    /**
     * Apply a result reported by the match server. Idempotence: a second
     * report for the same match is rejected with [SettleResult.AlreadySettled].
     */
    fun settle(report: MatchResultReport, serverToken: String): SettleResult {
        val id = runCatching { UUID.fromString(report.matchId) }.getOrNull()
            ?: return SettleResult.UnknownMatch
        val match = matches.get(id) ?: return SettleResult.UnknownMatch
        if (!java.security.MessageDigest.isEqual(
                match.serverToken.toByteArray(),
                serverToken.toByteArray()
            )
        ) return SettleResult.BadToken
        if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.VOIDED)
            return SettleResult.AlreadySettled

        val statsA = players.getStats(match.playerA, match.season)
            ?: error("stats for ${match.playerA} missing at settle")
        val statsB = players.getStats(match.playerB, match.season)
            ?: error("stats for ${match.playerB} missing at settle")

        val update = rating.update(
            playerA = RatingState(statsA.rating, statsA.matchesPlayed),
            playerB = RatingState(statsB.rating, statsB.matchesPlayed),
            outcome = report.outcome,
        )

        if (report.outcome != MatchOutcome.VOID) {
            players.upsertStats(
                statsA.copy(
                    rating = update.playerA.rating,
                    matchesPlayed = update.playerA.matchesPlayed,
                    wins = statsA.wins + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                    losses = statsA.losses + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                    draws = statsA.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
                )
            )
            players.upsertStats(
                statsB.copy(
                    rating = update.playerB.rating,
                    matchesPlayed = update.playerB.matchesPlayed,
                    wins = statsB.wins + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                    losses = statsB.losses + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                    draws = statsB.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
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
            completedAt = clock.instant(),
        )
        matches.update(settled)
        settledListeners.forEach { it(settled) }
        return SettleResult.Settled(settled)
    }

    companion object {
        /** 90 minutes — generous cap for lockout 1v1; tune with real data. */
        const val DEFAULT_TIME_LIMIT_SECONDS = 90L * 60L
    }
}
