package dev.yabranked.backend.match

import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.RatingState
import dev.yabranked.backend.rating.RatingSystem
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.backend.store.PlayerStore
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
 * Provisioning the actual game server is the orchestrator's job (Phase 2) —
 * it subscribes via [onMatchCreated].
 */
class MatchService(
    private val players: PlayerStore,
    private val matches: MatchStore,
    private val rating: RatingSystem,
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

    fun getOrCreatePlayer(uuid: UUID, name: String): PlayerRecord {
        players.get(uuid)?.let {
            // keep name current (players can rename their MC account)
            if (it.name != name) players.upsert(it.copy(name = name))
            return players.get(uuid)!!
        }
        val record = PlayerRecord(
            uuid = uuid,
            name = name,
            rating = rating.initialRating,
            matchesPlayed = 0,
            wins = 0,
            losses = 0,
            draws = 0,
            createdAt = clock.instant(),
        )
        players.upsert(record)
        return record
    }

    fun placementMatchesRemaining(player: PlayerRecord): Int =
        (placementMatches - player.matchesPlayed).coerceAtLeast(0)

    fun createMatch(queueMatch: QueueMatch, format: MatchFormat): MatchRecord {
        val a = players.get(queueMatch.playerA.uuid) ?: error("unknown player ${queueMatch.playerA.uuid}")
        val b = players.get(queueMatch.playerB.uuid) ?: error("unknown player ${queueMatch.playerB.uuid}")

        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        val record = MatchRecord(
            id = UUID.randomUUID(),
            format = format,
            settings = MatchSettings(
                format = format,
                worldSeed = random.nextLong(),
                cardSeed = random.nextLong(),
                timeLimitSeconds = DEFAULT_TIME_LIMIT_SECONDS,
            ),
            playerA = a.uuid,
            playerB = b.uuid,
            status = MatchStatus.PENDING,
            serverToken = token,
            outcome = null,
            ratingABefore = a.rating,
            ratingBBefore = b.rating,
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

        val a = players.get(match.playerA) ?: error("player ${match.playerA} missing at settle")
        val b = players.get(match.playerB) ?: error("player ${match.playerB} missing at settle")

        val update = rating.update(
            playerA = RatingState(a.rating, a.matchesPlayed),
            playerB = RatingState(b.rating, b.matchesPlayed),
            outcome = report.outcome,
        )

        if (report.outcome != MatchOutcome.VOID) {
            players.upsert(
                a.copy(
                    rating = update.playerA.rating,
                    matchesPlayed = update.playerA.matchesPlayed,
                    wins = a.wins + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                    losses = a.losses + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                    draws = a.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
                )
            )
            players.upsert(
                b.copy(
                    rating = update.playerB.rating,
                    matchesPlayed = update.playerB.matchesPlayed,
                    wins = b.wins + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                    losses = b.losses + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                    draws = b.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
                )
            )
        }

        val settled = match.copy(
            status = if (report.outcome == MatchOutcome.VOID) MatchStatus.VOIDED else MatchStatus.COMPLETED,
            outcome = report.outcome,
            ratingAAfter = update.playerA.rating,
            ratingBAfter = update.playerB.rating,
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
