package dev.yabranked.backend.store

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Account identity — season-independent. */
data class PlayerRecord(
    val uuid: UUID,
    val name: String,
    val bannedAt: Instant? = null,
    val createdAt: Instant,
    /** ISO 3166-1 alpha-2 country code (lowercase), null if unset. */
    val country: String? = null,
    /** Profile-card background id; "default" when unset. */
    val background: String = "default",
) {
    val isBanned: Boolean get() = bannedAt != null
}

/** Ladder stats for one player in one season. */
data class SeasonStats(
    val uuid: UUID,
    val season: Int,
    val rating: Int,
    val matchesPlayed: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    /** Cumulative seconds spent in counted matches this season. */
    val playtimeSeconds: Long = 0,
    /** Matches this player forfeited (concede or no-show) this season. */
    val forfeits: Int = 0,
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
    val season: Int,
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
    val durationSeconds: Long? = null,
    val teamAScore: Int? = null,
    val teamBScore: Int? = null,
    /** Player who forfeited this match (concede or no-show), null for a normal finish. */
    val forfeitedBy: UUID? = null,
    val createdAt: Instant,
    val completedAt: Instant?,
)

data class ReportRecord(
    val id: UUID,
    val matchId: UUID,
    val reporter: UUID,
    val accused: UUID,
    val reason: String,
    val createdAt: Instant,
)

/**
 * Persistence interfaces. In-memory implementations below; Postgres versions
 * replace them later (schema in backend/src/main/resources/schema.sql)
 * without touching callers.
 */
interface PlayerStore {
    fun getPlayer(uuid: UUID): PlayerRecord?
    fun upsertPlayer(record: PlayerRecord)
    fun getStats(uuid: UUID, season: Int): SeasonStats?
    fun upsertStats(stats: SeasonStats)
    fun topByRating(season: Int, limit: Int, minMatches: Int): List<SeasonStats>

    /** 1-based leaderboard rank, or null if not on the ladder yet. */
    fun rankOf(uuid: UUID, season: Int, minMatches: Int): Int?
}

interface MatchStore {
    fun get(id: UUID): MatchRecord?
    fun insert(record: MatchRecord)
    fun update(record: MatchRecord)
    fun historyFor(player: UUID, season: Int, limit: Int): List<MatchRecord>
}

interface ReportStore {
    fun insert(record: ReportRecord)
    fun list(limit: Int): List<ReportRecord>
    fun existsFor(matchId: UUID, reporter: UUID): Boolean
}

class InMemoryPlayerStore : PlayerStore {
    private val players = ConcurrentHashMap<UUID, PlayerRecord>()
    private val stats = ConcurrentHashMap<Pair<UUID, Int>, SeasonStats>()

    override fun getPlayer(uuid: UUID): PlayerRecord? = players[uuid]

    override fun upsertPlayer(record: PlayerRecord) {
        players[record.uuid] = record
    }

    override fun getStats(uuid: UUID, season: Int): SeasonStats? = stats[uuid to season]

    override fun upsertStats(stats: SeasonStats) {
        this.stats[stats.uuid to stats.season] = stats
    }

    override fun topByRating(season: Int, limit: Int, minMatches: Int): List<SeasonStats> =
        stats.values
            .filter { it.season == season && it.matchesPlayed >= minMatches }
            .sortedByDescending { it.rating }
            .take(limit)

    override fun rankOf(uuid: UUID, season: Int, minMatches: Int): Int? {
        val ladder = stats.values
            .filter { it.season == season && it.matchesPlayed >= minMatches }
            .sortedByDescending { it.rating }
        val index = ladder.indexOfFirst { it.uuid == uuid }
        if (index == -1) return null
        // standard competition ranking: ties share the better rank
        return ladder.count { it.rating > ladder[index].rating } + 1
    }
}

class InMemoryMatchStore : MatchStore {
    private val matches = ConcurrentHashMap<UUID, MatchRecord>()

    override fun get(id: UUID): MatchRecord? = matches[id]

    override fun insert(record: MatchRecord) {
        val previous = matches.putIfAbsent(record.id, record)
        require(previous == null) { "match ${record.id} already exists" }
    }

    override fun update(record: MatchRecord) {
        matches[record.id] = record
    }

    override fun historyFor(player: UUID, season: Int, limit: Int): List<MatchRecord> =
        matches.values
            .filter { it.season == season && (it.playerA == player || it.playerB == player) }
            .sortedByDescending { it.createdAt }
            .take(limit)
}

class InMemoryReportStore : ReportStore {
    private val reports = ConcurrentHashMap<UUID, ReportRecord>()

    override fun insert(record: ReportRecord) {
        reports[record.id] = record
    }

    override fun list(limit: Int): List<ReportRecord> =
        reports.values.sortedByDescending { it.createdAt }.take(limit)

    override fun existsFor(matchId: UUID, reporter: UUID): Boolean =
        reports.values.any { it.matchId == matchId && it.reporter == reporter }
}
