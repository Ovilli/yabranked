package dev.yabranked.backend.store

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import dev.yabranked.proto.PrivacySettings
import dev.yabranked.proto.Visibility
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
    /**
     * Hide the country flag from other players' views.
     *
     * Legacy mirror of [privacy]; both are persisted because clients that
     * predate the privacy block still read these two. [withPrivacy] is the only
     * thing that should ever set them, so the two views cannot disagree.
     */
    val hideFlag: Boolean = false,
    /** Hide the exact rating on the public profile and match-found reveal. */
    val hideRating: Boolean = false,
    /** Full privacy preferences; the authority for every gated profile field. */
    val privacy: PrivacySettings = PrivacySettings(),
) {
    val isBanned: Boolean get() = bannedAt != null

    /**
     * Set [privacy] and re-derive the legacy booleans from it. Anything less
     * than [Visibility.EVERYONE] reads as "hidden" to an old client, which is
     * the safe direction to round: it under-shares rather than over-shares.
     */
    fun withPrivacy(settings: PrivacySettings): PlayerRecord = copy(
        privacy = settings,
        hideFlag = settings.showCountry != Visibility.EVERYONE,
        hideRating = settings.showRating != Visibility.EVERYONE,
    )

    companion object {
        /**
         * Privacy for a row written before the block existed: reconstruct it
         * from the legacy booleans so an upgrade does not silently re-expose a
         * flag the player had hidden.
         */
        fun privacyFromLegacy(hideFlag: Boolean, hideRating: Boolean) = PrivacySettings(
            showCountry = if (hideFlag) Visibility.NOBODY else Visibility.EVERYONE,
            showRating = if (hideRating) Visibility.NOBODY else Visibility.EVERYONE,
        )
    }
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
    /**
     * Full side-ordered rosters. A 1v1 leaves this empty and is described by
     * [playerA]/[playerB] alone, which is what every pre-team query still reads;
     * team and party matches fill it in and keep [playerA]/[playerB] pointing at
     * each side's first player so those queries keep working.
     */
    val teams: List<List<UUID>> = emptyList(),
    /** Side-ordered final scores for matches with more than two sides. */
    val teamScores: List<Int> = emptyList(),
    /** Winning side index for matches with more than two sides. */
    val winningTeam: Int? = null,
    /** Party this match was started from, for party-match history. */
    val partyId: UUID? = null,
    /**
     * Whether this match may move ratings, decided once at creation.
     *
     * A rated format can still be played unrated — a party practising 2v2 with
     * hand-picked teams, say — and that decision has to survive a restart, so it
     * lives on the record rather than in the process that created it. Defaults
     * to true so every row written before the field existed keeps counting.
     */
    val rated: Boolean = true,
) {
    /** Reached a real result: it counts for streaks and head-to-head records. */
    val isDecided: Boolean get() = outcome != null && outcome != MatchOutcome.VOID

    /**
     * Side-ordered rosters, synthesising the two-side view for a record that
     * predates [teams]. Every team-aware caller reads this rather than the
     * field, so old and new rows look identical from the outside.
     */
    val rosters: List<List<UUID>>
        get() = if (teams.isNotEmpty()) teams else listOf(listOf(playerA), listOf(playerB))

    /** Every participant, in side order. */
    val participants: List<UUID> get() = rosters.flatten()

    /** Which side [player] is on, or null when they are not in this match. */
    fun sideOf(player: UUID): Int? = rosters.indexOfFirst { player in it }.takeIf { it >= 0 }

    /** [player]'s teammates, excluding themselves. */
    fun teammatesOf(player: UUID): List<UUID> =
        sideOf(player)?.let { rosters[it].filter { other -> other != player } } ?: emptyList()

    fun opponentsOf(player: UUID): List<UUID> {
        val side = sideOf(player) ?: return emptyList()
        return rosters.filterIndexed { index, _ -> index != side }.flatten()
    }

    /**
     * Whether [player] won. Two-side matches read [outcome]; anything wider
     * reads [winningTeam], which is the only thing that can express a winner
     * among three or more sides.
     */
    fun didWin(player: UUID): Boolean {
        val side = sideOf(player) ?: return false
        winningTeam?.let { return it == side }
        return when (outcome) {
            MatchOutcome.TEAM_A_WIN -> side == 0
            MatchOutcome.TEAM_B_WIN -> side == 1
            else -> false
        }
    }
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
     * Like [getPlayer], but inside a transaction it also takes a row lock.
     * Anything that rewrites a whole [PlayerRecord] has to read it through this
     * — [upsertPlayer] replaces every column, so a row read outside the lock and
     * written back later silently reverts whatever landed in between.
     */
    fun getPlayerForUpdate(uuid: UUID): PlayerRecord? = getPlayer(uuid)

    /**
     * [getPlayer] for many accounts at once. Unknown uuids are simply absent
     * from the result; callers that render a list of players would otherwise
     * issue one query per row.
     */
    fun getPlayers(uuids: Collection<UUID>): Map<UUID, PlayerRecord>

    fun upsertPlayer(record: PlayerRecord)

    /**
     * Exact name match, case-insensitive; null when nobody has used the mod
     * under that name. Minecraft names are unique and re-assignable, so this
     * resolves whoever currently holds it — which is the same account the
     * player just saw in-game.
     */
    fun findByName(name: String): PlayerRecord?

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

    /**
     * The match this player is currently in, newest first, or null.
     *
     * Separate from [unsettled] because the client asks this on an interval to
     * find out whether the match it thinks it is in is really still running —
     * scanning every live match in the process to answer for one player is the
     * shape of query that is fine in memory and not fine in Postgres.
     */
    fun liveFor(player: UUID): MatchRecord?
}

interface ReportStore {
    fun insert(record: ReportRecord)
    fun list(limit: Int): List<ReportRecord>
    fun existsFor(matchId: UUID, reporter: UUID): Boolean

    /**
     * One report per accused per match, rather than one per match.
     *
     * A 4v4 has four opponents and they do not misbehave as a unit; the
     * coarser check meant reporting one of them silently consumed the
     * reporter's only report for that game.
     */
    fun existsFor(matchId: UUID, reporter: UUID, accused: UUID): Boolean

    /** Every report filed about [matchId], for moderator review. */
    fun forMatch(matchId: UUID): List<ReportRecord>
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

    override fun findByName(name: String): PlayerRecord? =
        players.values.firstOrNull { it.name.equals(name, ignoreCase = true) }

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

    // Membership goes through `participants`/`sideOf` rather than playerA/playerB:
    // in a team match those two are only the first player of each side, so
    // matching on them alone would hide four of a 3v3's six players' own games.
    override fun historyFor(player: UUID, season: Int, limit: Int): List<MatchRecord> =
        matches.values
            .filter { it.season == season && player in it.participants }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun recentDecided(player: UUID, season: Int, limit: Int): List<MatchRecord> =
        matches.values
            .filter { it.season == season && player in it.participants }
            .filter { it.isDecided }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun between(a: UUID, b: UUID, season: Int, limit: Int): List<MatchRecord> =
        matches.values
            .filter {
                val sideA = it.sideOf(a)
                val sideB = it.sideOf(b)
                it.season == season && sideA != null && sideB != null && sideA != sideB
            }
            .filter { it.isDecided }
            .sortedByDescending { it.createdAt }
            .take(limit)

    override fun unsettled(): List<MatchRecord> =
        matches.values.filter { it.status == MatchStatus.PENDING || it.status == MatchStatus.ACTIVE }

    override fun liveFor(player: UUID): MatchRecord? =
        unsettled().filter { player in it.participants }.maxByOrNull { it.createdAt }
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

    override fun existsFor(matchId: UUID, reporter: UUID, accused: UUID): Boolean =
        reports.values.any {
            it.matchId == matchId && it.reporter == reporter && it.accused == accused
        }

    override fun forMatch(matchId: UUID): List<ReportRecord> =
        reports.values.filter { it.matchId == matchId }.sortedBy { it.createdAt }
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
