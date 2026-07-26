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
    /** Hide the country flag from other players' views. */
    val hideFlag: Boolean = false,
    /** Hide the exact rating on the public profile and match-found reveal. */
    val hideRating: Boolean = false,
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
    /** Highest rating reached this season. */
    val peakRating: Int = rating,
    /**
     * When this player last settled a counted match. Null for a row that has
     * never been played (a season rollover seeds those) and for rows written
     * before the column existed — inactivity decay treats both as "unknown",
     * not as "inactive forever".
     */
    val lastPlayedAt: Instant? = null,
    /**
     * How far inactivity decay has already been charged for this row. The decay
     * sweep bills whole idle days and moves this forward, so running it twice in
     * a day (or after a restart) cannot bill the same days again. Cleared
     * whenever the player plays.
     */
    val decayedThrough: Instant? = null,
)

/**
 * A player's totals across every season. Achievements are lifetime awards, so
 * their predicates run against this rather than the current season's counters
 * (see [dev.yabranked.backend.achievement.AchievementContext]).
 */
data class LifetimeStats(
    val uuid: UUID,
    val matchesPlayed: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val playtimeSeconds: Long,
    val forfeits: Int,
    /** Highest rating ever reached, in any season. */
    val peakRating: Int,
) {
    companion object {
        /** Fold a player's season rows into one lifetime total. */
        fun of(uuid: UUID, seasons: Collection<SeasonStats>) = LifetimeStats(
            uuid = uuid,
            matchesPlayed = seasons.sumOf { it.matchesPlayed },
            wins = seasons.sumOf { it.wins },
            losses = seasons.sumOf { it.losses },
            draws = seasons.sumOf { it.draws },
            playtimeSeconds = seasons.sumOf { it.playtimeSeconds },
            forfeits = seasons.sumOf { it.forfeits },
            peakRating = seasons.maxOfOrNull { maxOf(it.peakRating, it.rating) } ?: 0,
        )
    }
}

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
) {
    /** Reached a real result: it counts for streaks and head-to-head records. */
    val isDecided: Boolean get() = outcome != null && outcome != MatchOutcome.VOID
}

/**
 * One ladder row: season stats together with the account they belong to.
 * Reading the two separately meant a query per leaderboard entry.
 */
data class LadderEntry(
    val stats: SeasonStats,
    /** Null only if the account row disappeared under the stats row. */
    val player: PlayerRecord?,
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
/**
 * Runs [block] so that every store call it makes is atomic: all writes commit
 * together or none do. Nesting joins the outer transaction.
 *
 * The read-modify-write sequences in the match settle path are only safe
 * against concurrent settles when wrapped in one of these *and* the rows are
 * read with the `…ForUpdate` accessors below.
 */
interface TransactionRunner {
    fun <T> transaction(block: () -> T): T
}

/**
 * Transaction runner for the in-memory stores: they have no transactions, so
 * atomicity is bought with a single global lock. Reentrant, so nesting works.
 */
class LockingTransactionRunner : TransactionRunner {
    private val lock = java.util.concurrent.locks.ReentrantLock()

    override fun <T> transaction(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

interface PlayerStore {
    fun getPlayer(uuid: UUID): PlayerRecord?

    /**
     * [getPlayer] for many accounts at once. Unknown uuids are simply absent
     * from the result; callers that render a list of players would otherwise
     * issue one query per row.
     */
    fun getPlayers(uuids: Collection<UUID>): Map<UUID, PlayerRecord>

    fun upsertPlayer(record: PlayerRecord)
    fun getStats(uuid: UUID, season: Int): SeasonStats?

    /**
     * Like [getStats], but inside a transaction it also takes a row lock, so a
     * concurrent settle for the same player blocks instead of overwriting.
     * Lock rows in a deterministic order (ascending uuid) to avoid deadlocks.
     */
    fun getStatsForUpdate(uuid: UUID, season: Int): SeasonStats? = getStats(uuid, season)

    fun upsertStats(stats: SeasonStats)

    /** Totals over every season this player has a row in. Never null: an account with no rows is all zeroes. */
    fun lifetimeStats(uuid: UUID): LifetimeStats

    fun topByRating(season: Int, limit: Int, minMatches: Int): List<SeasonStats>

    /**
     * [topByRating] with each row's account attached — the whole leaderboard in
     * one round trip instead of a player lookup per entry.
     */
    fun leaderboard(season: Int, limit: Int, minMatches: Int): List<LadderEntry>

    /** 1-based leaderboard rank, or null if not on the ladder yet. */
    fun rankOf(uuid: UUID, season: Int, minMatches: Int): Int?
}

interface MatchStore {
    fun get(id: UUID): MatchRecord?

    /** [get] plus a row lock when inside a transaction; see [PlayerStore.getStatsForUpdate]. */
    fun getForUpdate(id: UUID): MatchRecord? = get(id)

    fun insert(record: MatchRecord)
    fun update(record: MatchRecord)
    fun historyFor(player: UUID, season: Int, limit: Int): List<MatchRecord>

    /**
     * [historyFor] restricted to matches that reached a real result. Voided and
     * still-running matches neither continue nor break a win streak, so asking
     * for the decided ones keeps the streak window as small as the streak is.
     */
    fun recentDecided(player: UUID, season: Int, limit: Int): List<MatchRecord>

    /**
     * Decided matches these two played against each other, newest first. The
     * head-to-head record used to be a wide window of one player's history
     * filtered down in memory.
     */
    fun between(a: UUID, b: UUID, season: Int, limit: Int): List<MatchRecord>

    /**
     * Matches left PENDING or ACTIVE — i.e. still owed a result. After a
     * restart these are orphans: their match servers (and the timers watching
     * them) died with the previous process.
     */
    fun unsettled(): List<MatchRecord>
}

interface ReportStore {
    fun insert(record: ReportRecord)
    fun list(limit: Int): List<ReportRecord>
    fun existsFor(matchId: UUID, reporter: UUID): Boolean
}

/** One unlocked achievement, identified by its stable catalog id. */
data class AchievementRecord(val achievementId: String, val earnedAt: Instant)

interface AchievementStore {
    /** All milestones this player has unlocked, in no particular order. */
    fun earned(uuid: UUID): List<AchievementRecord>

    /**
     * Record an unlock. No-op if the player already has it; returns true only
     * when this call is the one that first awarded it.
     */
    fun award(uuid: UUID, achievementId: String, earnedAt: Instant): Boolean
}

class InMemoryPlayerStore : PlayerStore {
    private val players = ConcurrentHashMap<UUID, PlayerRecord>()
    private val stats = ConcurrentHashMap<Pair<UUID, Int>, SeasonStats>()

    override fun getPlayer(uuid: UUID): PlayerRecord? = players[uuid]

    override fun getPlayers(uuids: Collection<UUID>): Map<UUID, PlayerRecord> =
        uuids.distinct().mapNotNull(players::get).associateBy { it.uuid }

    override fun upsertPlayer(record: PlayerRecord) {
        players[record.uuid] = record
    }

    override fun getStats(uuid: UUID, season: Int): SeasonStats? = stats[uuid to season]

    override fun upsertStats(stats: SeasonStats) {
        this.stats[stats.uuid to stats.season] = stats
    }

    override fun lifetimeStats(uuid: UUID): LifetimeStats =
        LifetimeStats.of(uuid, stats.values.filter { it.uuid == uuid })

    override fun topByRating(season: Int, limit: Int, minMatches: Int): List<SeasonStats> =
        stats.values
            .filter { it.season == season && it.matchesPlayed >= minMatches }
            .sortedByDescending { it.rating }
            .take(limit)

    override fun leaderboard(season: Int, limit: Int, minMatches: Int): List<LadderEntry> =
        topByRating(season, limit, minMatches).map { LadderEntry(it, players[it.uuid]) }

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

    override fun recentDecided(player: UUID, season: Int, limit: Int): List<MatchRecord> =
        matches.values
            .filter { it.season == season && (it.playerA == player || it.playerB == player) }
            .filter { it.isDecided }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun between(a: UUID, b: UUID, season: Int, limit: Int): List<MatchRecord> =
        matches.values
            .filter {
                it.season == season &&
                    ((it.playerA == a && it.playerB == b) || (it.playerA == b && it.playerB == a))
            }
            .filter { it.isDecided }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun unsettled(): List<MatchRecord> =
        matches.values.filter { it.status == MatchStatus.PENDING || it.status == MatchStatus.ACTIVE }
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

class InMemoryAchievementStore : AchievementStore {
    private val earned = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Instant>>()

    override fun earned(uuid: UUID): List<AchievementRecord> =
        earned[uuid]?.map { AchievementRecord(it.key, it.value) } ?: emptyList()

    override fun award(uuid: UUID, achievementId: String, earnedAt: Instant): Boolean {
        val forPlayer = earned.getOrPut(uuid) { ConcurrentHashMap() }
        return forPlayer.putIfAbsent(achievementId, earnedAt) == null
    }
}
