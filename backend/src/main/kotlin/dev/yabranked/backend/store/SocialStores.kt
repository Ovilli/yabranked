package dev.yabranked.backend.store

import dev.yabranked.proto.EndorsementCategory
import dev.yabranked.proto.MatchFormat
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistence for the social systems: friendships, friend requests,
 * endorsements and per-mode ladders.
 *
 * Same shape as [Stores.kt]: an interface per concern, an in-memory
 * implementation here, a Postgres one in `PostgresSocialStores.kt`.
 */

/**
 * An accepted friendship. Stored once, not twice: [a] is always the smaller
 * uuid, so "are these two friends" is one lookup and the pair can never end up
 * half-recorded.
 */
data class FriendshipRecord(val a: UUID, val b: UUID, val since: Instant) {
    init {
        require(a < b) { "friendship must be stored with the lower uuid first" }
    }

    fun other(self: UUID): UUID = if (self == a) b else a

    companion object {
        fun of(x: UUID, y: UUID, since: Instant): FriendshipRecord =
            if (x < y) FriendshipRecord(x, y, since) else FriendshipRecord(y, x, since)
    }
}

data class FriendRequestRecord(
    val id: UUID,
    val from: UUID,
    val to: UUID,
    val createdAt: Instant,
)

interface FriendStore {
    /** Every accepted friend of [uuid]. */
    fun friendsOf(uuid: UUID): List<FriendshipRecord>

    fun areFriends(a: UUID, b: UUID): Boolean

    /** Idempotent: re-adding an existing friendship keeps the original [since]. */
    fun addFriend(a: UUID, b: UUID, since: Instant): Boolean

    fun removeFriend(a: UUID, b: UUID): Boolean

    fun friendCount(uuid: UUID): Int

    /** Pending requests addressed to [uuid]. */
    fun incoming(uuid: UUID): List<FriendRequestRecord>

    /** Pending requests [uuid] sent. */
    fun outgoing(uuid: UUID): List<FriendRequestRecord>

    /** A pending request in either direction between the two, if any. */
    fun requestBetween(a: UUID, b: UUID): FriendRequestRecord?

    fun getRequest(id: UUID): FriendRequestRecord?

    /** Returns false when an identical request already exists. */
    fun insertRequest(record: FriendRequestRecord): Boolean

    fun deleteRequest(id: UUID): Boolean

    /** Drops every request between the two, in both directions. */
    fun deleteRequestsBetween(a: UUID, b: UUID)
}

/**
 * One teammate endorsement. The primary key is (match, from, to): a player may
 * endorse each teammate once per match and no more, which is what stops a
 * five-minute rematch loop from farming levels.
 */
data class EndorsementRecord(
    val matchId: UUID,
    val from: UUID,
    val to: UUID,
    val category: EndorsementCategory,
    val createdAt: Instant,
)

interface EndorsementStore {
    /** False when this exact endorsement already exists. */
    fun insert(record: EndorsementRecord): Boolean

    /** Whether [from] has already endorsed anyone for [matchId]. */
    fun hasEndorsed(matchId: UUID, from: UUID): Boolean

    /** Lifetime endorsement count received by [uuid]. */
    fun totalFor(uuid: UUID): Int

    /** Lifetime counts received by [uuid], per category. */
    fun countsFor(uuid: UUID): Map<EndorsementCategory, Int>

    /** Lifetime totals for many players at once, for leaderboards. */
    fun totalsFor(uuids: Collection<UUID>): Map<UUID, Int>

    /** Highest lifetime totals, for the endorsement leaderboard. */
    fun top(limit: Int): List<Pair<UUID, Int>>
}

/**
 * One player's counters in one mode in one season.
 *
 * [rating] is that mode's own ladder: a 3v3 rating says nothing about 1v1
 * skill, so they are never mixed. Unrated modes carry a rating too (it is
 * simply never read), which keeps the row shape uniform.
 */
data class ModeStatsRecord(
    val uuid: UUID,
    val season: Int,
    val format: MatchFormat,
    val rating: Int,
    val matchesPlayed: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val draws: Int = 0,
    val playtimeSeconds: Long = 0,
    val forfeits: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val peakRating: Int = rating,
)

interface ModeStatsStore {
    fun get(uuid: UUID, season: Int, format: MatchFormat): ModeStatsRecord?

    /** [get] with a row lock inside a transaction; see [PlayerStore.getStatsForUpdate]. */
    fun getForUpdate(uuid: UUID, season: Int, format: MatchFormat): ModeStatsRecord? =
        get(uuid, season, format)

    /** Every mode this player has a row in, for the profile's mode breakdown. */
    fun allFor(uuid: UUID, season: Int): List<ModeStatsRecord>

    fun upsert(record: ModeStatsRecord)

    /** Top of one mode's ladder. */
    fun top(season: Int, format: MatchFormat, limit: Int, minMatches: Int): List<ModeStatsRecord>

    /** 1-based rank in one mode's ladder, or null when not on it. */
    fun rankOf(uuid: UUID, season: Int, format: MatchFormat, minMatches: Int): Int?

    /**
     * Most seconds played this season, summed across every mode. The board for
     * players whose contribution is time rather than rating.
     */
    fun topPlaytime(season: Int, limit: Int): List<Pair<UUID, Long>>

    /** Longest win streak held this season, in any single mode. */
    fun topStreak(season: Int, limit: Int): List<Pair<UUID, Int>>

    /** Most wins this season, summed across every mode. */
    fun topWins(season: Int, limit: Int): List<Pair<UUID, Int>>
}

// --- In-memory implementations ---

class InMemoryFriendStore : FriendStore {
    private val friendships = ConcurrentHashMap<Pair<UUID, UUID>, FriendshipRecord>()
    private val requests = ConcurrentHashMap<UUID, FriendRequestRecord>()

    private fun key(a: UUID, b: UUID) = if (a < b) a to b else b to a

    override fun friendsOf(uuid: UUID): List<FriendshipRecord> =
        friendships.values.filter { it.a == uuid || it.b == uuid }

    override fun areFriends(a: UUID, b: UUID): Boolean = friendships.containsKey(key(a, b))

    override fun addFriend(a: UUID, b: UUID, since: Instant): Boolean {
        if (a == b) return false
        return friendships.putIfAbsent(key(a, b), FriendshipRecord.of(a, b, since)) == null
    }

    override fun removeFriend(a: UUID, b: UUID): Boolean = friendships.remove(key(a, b)) != null

    override fun friendCount(uuid: UUID): Int = friendsOf(uuid).size

    override fun incoming(uuid: UUID): List<FriendRequestRecord> =
        requests.values.filter { it.to == uuid }.sortedByDescending { it.createdAt }

    override fun outgoing(uuid: UUID): List<FriendRequestRecord> =
        requests.values.filter { it.from == uuid }.sortedByDescending { it.createdAt }

    override fun requestBetween(a: UUID, b: UUID): FriendRequestRecord? =
        requests.values.firstOrNull {
            (it.from == a && it.to == b) || (it.from == b && it.to == a)
        }

    override fun getRequest(id: UUID): FriendRequestRecord? = requests[id]

    override fun insertRequest(record: FriendRequestRecord): Boolean {
        if (requests.values.any { it.from == record.from && it.to == record.to }) return false
        requests[record.id] = record
        return true
    }

    override fun deleteRequest(id: UUID): Boolean = requests.remove(id) != null

    override fun deleteRequestsBetween(a: UUID, b: UUID) {
        requests.entries.removeIf {
            val r = it.value
            (r.from == a && r.to == b) || (r.from == b && r.to == a)
        }
    }
}

class InMemoryEndorsementStore : EndorsementStore {
    private val records = ConcurrentHashMap<Triple<UUID, UUID, UUID>, EndorsementRecord>()

    override fun insert(record: EndorsementRecord): Boolean =
        records.putIfAbsent(Triple(record.matchId, record.from, record.to), record) == null

    override fun hasEndorsed(matchId: UUID, from: UUID): Boolean =
        records.values.any { it.matchId == matchId && it.from == from }

    override fun totalFor(uuid: UUID): Int = records.values.count { it.to == uuid }

    override fun countsFor(uuid: UUID): Map<EndorsementCategory, Int> =
        records.values.filter { it.to == uuid }.groupingBy { it.category }.eachCount()

    override fun totalsFor(uuids: Collection<UUID>): Map<UUID, Int> {
        val wanted = uuids.toSet()
        return records.values.filter { it.to in wanted }.groupingBy { it.to }.eachCount()
    }

    override fun top(limit: Int): List<Pair<UUID, Int>> =
        records.values.groupingBy { it.to }.eachCount().entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key to it.value }
}

class InMemoryModeStatsStore : ModeStatsStore {
    private val rows = ConcurrentHashMap<Triple<UUID, Int, MatchFormat>, ModeStatsRecord>()

    override fun get(uuid: UUID, season: Int, format: MatchFormat): ModeStatsRecord? =
        rows[Triple(uuid, season, format)]

    override fun allFor(uuid: UUID, season: Int): List<ModeStatsRecord> =
        rows.values.filter { it.uuid == uuid && it.season == season }

    override fun upsert(record: ModeStatsRecord) {
        rows[Triple(record.uuid, record.season, record.format)] = record
    }

    override fun top(season: Int, format: MatchFormat, limit: Int, minMatches: Int): List<ModeStatsRecord> =
        rows.values
            .filter { it.season == season && it.format == format && it.matchesPlayed >= minMatches }
            .sortedByDescending { it.rating }
            .take(limit)

    override fun rankOf(uuid: UUID, season: Int, format: MatchFormat, minMatches: Int): Int? {
        val ladder = rows.values
            .filter { it.season == season && it.format == format && it.matchesPlayed >= minMatches }
            .sortedByDescending { it.rating }
        val row = ladder.firstOrNull { it.uuid == uuid } ?: return null
        // standard competition ranking: ties share the better rank
        return ladder.count { it.rating > row.rating } + 1
    }

    private fun <T : Comparable<T>> topBy(
        season: Int,
        limit: Int,
        fold: (List<ModeStatsRecord>) -> T,
    ): List<Pair<UUID, T>> =
        rows.values
            .filter { it.season == season }
            .groupBy { it.uuid }
            .map { (uuid, modes) -> uuid to fold(modes) }
            .sortedByDescending { it.second }
            .take(limit)

    override fun topPlaytime(season: Int, limit: Int): List<Pair<UUID, Long>> =
        topBy(season, limit) { modes -> modes.sumOf { it.playtimeSeconds } }

    override fun topStreak(season: Int, limit: Int): List<Pair<UUID, Int>> =
        topBy(season, limit) { modes -> modes.maxOf { it.bestStreak } }

    override fun topWins(season: Int, limit: Int): List<Pair<UUID, Int>> =
        topBy(season, limit) { modes -> modes.sumOf { it.wins } }
}
