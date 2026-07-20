package dev.yabranked.backend.store

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import java.time.Instant
import java.util.UUID

data class PlayerRecord(
    val uuid: UUID,
    val name: String,
    val rating: Int,
    val matchesPlayed: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val createdAt: Instant,
)

enum class MatchStatus {
    /** Created, waiting for a match server to be provisioned. */
    PENDING,
    /** Match server up, players connecting/playing. */
    ACTIVE,
    /** Result reported and ratings applied. */
    COMPLETED,
    /** Voided (crash/abandon); no rating change. */
    VOIDED,
}

data class MatchRecord(
    val id: UUID,
    val format: MatchFormat,
    val settings: MatchSettings,
    val playerA: UUID,
    val playerB: UUID,
    val status: MatchStatus,
    /** Secret the match server uses to authenticate its result report. */
    val serverToken: String,
    /** host:port of the provisioned match server; null until provisioning completes. */
    val serverAddress: String? = null,
    val outcome: MatchOutcome?,
    val ratingABefore: Int,
    val ratingBBefore: Int,
    val ratingAAfter: Int?,
    val ratingBAfter: Int?,
    val createdAt: Instant,
    val completedAt: Instant?,
)

/**
 * Persistence interfaces. Phase 1 ships in-memory implementations;
 * Phase 2+ replaces them with Postgres (schema in backend/src/main/resources/schema.sql)
 * without touching callers.
 */
interface PlayerStore {
    fun get(uuid: UUID): PlayerRecord?
    fun upsert(record: PlayerRecord)
    fun topByRating(limit: Int, minMatches: Int): List<PlayerRecord>
}

interface MatchStore {
    fun get(id: UUID): MatchRecord?
    fun insert(record: MatchRecord)
    fun update(record: MatchRecord)
    fun historyFor(player: UUID, limit: Int): List<MatchRecord>
}

class InMemoryPlayerStore : PlayerStore {
    private val players = java.util.concurrent.ConcurrentHashMap<UUID, PlayerRecord>()

    override fun get(uuid: UUID): PlayerRecord? = players[uuid]

    override fun upsert(record: PlayerRecord) {
        players[record.uuid] = record
    }

    override fun topByRating(limit: Int, minMatches: Int): List<PlayerRecord> =
        players.values
            .filter { it.matchesPlayed >= minMatches }
            .sortedByDescending { it.rating }
            .take(limit)
}

class InMemoryMatchStore : MatchStore {
    private val matches = java.util.concurrent.ConcurrentHashMap<UUID, MatchRecord>()

    override fun get(id: UUID): MatchRecord? = matches[id]

    override fun insert(record: MatchRecord) {
        val previous = matches.putIfAbsent(record.id, record)
        require(previous == null) { "match ${record.id} already exists" }
    }

    override fun update(record: MatchRecord) {
        matches[record.id] = record
    }

    override fun historyFor(player: UUID, limit: Int): List<MatchRecord> =
        matches.values
            .filter { it.playerA == player || it.playerB == player }
            .sortedByDescending { it.createdAt }
            .take(limit)
}
