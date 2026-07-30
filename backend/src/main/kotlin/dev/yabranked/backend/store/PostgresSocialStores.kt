package dev.yabranked.backend.store

import dev.yabranked.proto.EndorsementCategory
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.PrivacySettings
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Codec for the columns that hold structured values.
 *
 * Rosters, per-side seeds and the privacy block are all small, always read as a
 * whole, and never queried on individually — so they are stored as JSON text
 * rather than as extra tables or a column per field, which would have meant a
 * migration every time a privacy toggle or a party option was added.
 *
 * Every decoder tolerates null and garbage: a column written by a newer backend
 * (or hand-edited) must degrade to the legacy shape, never take the process
 * down inside a result-set mapper.
 */
internal object SocialJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodePrivacy(value: PrivacySettings): String = json.encodeToString(PrivacySettings.serializer(), value)

    fun decodePrivacy(raw: String): PrivacySettings? =
        runCatching { json.decodeFromString(PrivacySettings.serializer(), raw) }.getOrNull()

    fun encodeTeams(teams: List<List<UUID>>): String? =
        if (teams.isEmpty()) null
        else json.encodeToString(
            ListSerializer(ListSerializer(String.serializer())),
            teams.map { side -> side.map(UUID::toString) },
        )

    fun decodeTeams(raw: String?): List<List<UUID>> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ListSerializer(String.serializer())), raw)
                .map { side -> side.map(UUID::fromString) }
        }.getOrDefault(emptyList())
    }

    fun encodeInts(values: List<Int>): String? =
        if (values.isEmpty()) null else json.encodeToString(ListSerializer(Int.serializer()), values)

    fun decodeInts(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Int.serializer()), raw) }
            .getOrDefault(emptyList())
    }

    fun encodeLongs(values: List<Long>): String? =
        if (values.isEmpty()) null else json.encodeToString(ListSerializer(Long.serializer()), values)

    fun decodeLongs(raw: String?): List<Long> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(Long.serializer()), raw) }
            .getOrDefault(emptyList())
    }
}

class PostgresFriendStore(private val db: Database) : FriendStore {

    private fun ResultSet.toFriendship() = FriendshipRecord(
        a = getObject("a", UUID::class.java),
        b = getObject("b", UUID::class.java),
        since = getTimestamp("since").toInstant(),
    )

    private fun ResultSet.toRequest() = FriendRequestRecord(
        id = getObject("id", UUID::class.java),
        from = getObject("from_uuid", UUID::class.java),
        to = getObject("to_uuid", UUID::class.java),
        createdAt = getTimestamp("created_at").toInstant(),
    )

    override fun friendsOf(uuid: UUID): List<FriendshipRecord> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM friendships WHERE a = ? OR b = ? ORDER BY since DESC").use { s ->
            s.setObject(1, uuid)
            s.setObject(2, uuid)
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.toFriendship()) } }
        }
    }

    override fun areFriends(a: UUID, b: UUID): Boolean {
        if (a == b) return false
        val (low, high) = order(a, b)
        return db.withConnection { c ->
            c.prepareStatement("SELECT 1 FROM friendships WHERE a = ? AND b = ?").use { s ->
                s.setObject(1, low)
                s.setObject(2, high)
                s.executeQuery().use { it.next() }
            }
        }
    }

    override fun addFriend(a: UUID, b: UUID, since: Instant): Boolean {
        if (a == b) return false
        val (low, high) = order(a, b)
        return db.withConnection { c ->
            // DO NOTHING, not DO UPDATE: re-adding keeps the original `since`,
            // so "friends since" cannot be reset by pressing the button twice.
            c.prepareStatement(
                "INSERT INTO friendships (a, b, since) VALUES (?, ?, ?) ON CONFLICT (a, b) DO NOTHING"
            ).use { s ->
                s.setObject(1, low)
                s.setObject(2, high)
                s.setTimestamp(3, Timestamp.from(since))
                s.executeUpdate() > 0
            }
        }
    }

    override fun removeFriend(a: UUID, b: UUID): Boolean {
        val (low, high) = order(a, b)
        return db.withConnection { c ->
            c.prepareStatement("DELETE FROM friendships WHERE a = ? AND b = ?").use { s ->
                s.setObject(1, low)
                s.setObject(2, high)
                s.executeUpdate() > 0
            }
        }
    }

    override fun friendCount(uuid: UUID): Int = db.withConnection { c ->
        c.prepareStatement("SELECT COUNT(*)::int FROM friendships WHERE a = ? OR b = ?").use { s ->
            s.setObject(1, uuid)
            s.setObject(2, uuid)
            s.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 }
        }
    }

    override fun incoming(uuid: UUID): List<FriendRequestRecord> = requests("to_uuid", uuid)

    override fun outgoing(uuid: UUID): List<FriendRequestRecord> = requests("from_uuid", uuid)

    private fun requests(column: String, uuid: UUID): List<FriendRequestRecord> = db.withConnection { c ->
        c.prepareStatement(
            "SELECT * FROM friend_requests WHERE $column = ? ORDER BY created_at DESC"
        ).use { s ->
            s.setObject(1, uuid)
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.toRequest()) } }
        }
    }

    override fun requestBetween(a: UUID, b: UUID): FriendRequestRecord? = db.withConnection { c ->
        c.prepareStatement(
            """
            SELECT * FROM friend_requests
            WHERE (from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)
            ORDER BY created_at DESC LIMIT 1
            """.trimIndent()
        ).use { s ->
            s.setObject(1, a)
            s.setObject(2, b)
            s.setObject(3, b)
            s.setObject(4, a)
            s.executeQuery().use { r -> if (r.next()) r.toRequest() else null }
        }
    }

    override fun getRequest(id: UUID): FriendRequestRecord? = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM friend_requests WHERE id = ?").use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r -> if (r.next()) r.toRequest() else null }
        }
    }

    override fun insertRequest(record: FriendRequestRecord): Boolean = db.withConnection { c ->
        c.prepareStatement(
            """
            INSERT INTO friend_requests (id, from_uuid, to_uuid, created_at)
            VALUES (?, ?, ?, ?) ON CONFLICT (from_uuid, to_uuid) DO NOTHING
            """.trimIndent()
        ).use { s ->
            s.setObject(1, record.id)
            s.setObject(2, record.from)
            s.setObject(3, record.to)
            s.setTimestamp(4, Timestamp.from(record.createdAt))
            s.executeUpdate() > 0
        }
    }

    override fun deleteRequest(id: UUID): Boolean = db.withConnection { c ->
        c.prepareStatement("DELETE FROM friend_requests WHERE id = ?").use { s ->
            s.setObject(1, id)
            s.executeUpdate() > 0
        }
    }

    override fun deleteRequestsBetween(a: UUID, b: UUID) {
        db.withConnection { c ->
            c.prepareStatement(
                """
                DELETE FROM friend_requests
                WHERE (from_uuid = ? AND to_uuid = ?) OR (from_uuid = ? AND to_uuid = ?)
                """.trimIndent()
            ).use { s ->
                s.setObject(1, a)
                s.setObject(2, b)
                s.setObject(3, b)
                s.setObject(4, a)
                s.executeUpdate()
            }
        }
    }

    private fun order(a: UUID, b: UUID): Pair<UUID, UUID> = if (a < b) a to b else b to a
}

class PostgresEndorsementStore(private val db: Database) : EndorsementStore {

    override fun insert(record: EndorsementRecord): Boolean = db.withConnection { c ->
        // The primary key is the "once per teammate per match" rule; the
        // conflict clause is what turns a double-click into a no-op.
        c.prepareStatement(
            """
            INSERT INTO endorsements (match_id, from_uuid, to_uuid, category, created_at)
            VALUES (?, ?, ?, ?, ?) ON CONFLICT (match_id, from_uuid, to_uuid) DO NOTHING
            """.trimIndent()
        ).use { s ->
            s.setObject(1, record.matchId)
            s.setObject(2, record.from)
            s.setObject(3, record.to)
            s.setString(4, record.category.name)
            s.setTimestamp(5, Timestamp.from(record.createdAt))
            s.executeUpdate() > 0
        }
    }

    override fun hasEndorsed(matchId: UUID, from: UUID): Boolean = db.withConnection { c ->
        c.prepareStatement("SELECT 1 FROM endorsements WHERE match_id = ? AND from_uuid = ? LIMIT 1").use { s ->
            s.setObject(1, matchId)
            s.setObject(2, from)
            s.executeQuery().use { it.next() }
        }
    }

    override fun totalFor(uuid: UUID): Int = db.withConnection { c ->
        c.prepareStatement("SELECT COUNT(*)::int FROM endorsements WHERE to_uuid = ?").use { s ->
            s.setObject(1, uuid)
            s.executeQuery().use { r -> if (r.next()) r.getInt(1) else 0 }
        }
    }

    override fun countsFor(uuid: UUID): Map<EndorsementCategory, Int> = db.withConnection { c ->
        c.prepareStatement(
            "SELECT category, COUNT(*)::int AS n FROM endorsements WHERE to_uuid = ? GROUP BY category"
        ).use { s ->
            s.setObject(1, uuid)
            s.executeQuery().use { r ->
                buildMap {
                    while (r.next()) {
                        // A category retired from the enum is dropped rather
                        // than crashing the profile that still has one.
                        val category = runCatching { EndorsementCategory.valueOf(r.getString("category")) }
                            .getOrNull() ?: continue
                        put(category, r.getInt("n"))
                    }
                }
            }
        }
    }

    override fun totalsFor(uuids: Collection<UUID>): Map<UUID, Int> {
        val wanted = uuids.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT to_uuid, COUNT(*)::int AS n FROM endorsements
                WHERE to_uuid = ANY (?) GROUP BY to_uuid
                """.trimIndent()
            ).use { s ->
                s.setArray(1, c.createArrayOf("uuid", wanted.toTypedArray()))
                s.executeQuery().use { r ->
                    buildMap { while (r.next()) put(r.getObject("to_uuid", UUID::class.java), r.getInt("n")) }
                }
            }
        }
    }

    override fun top(limit: Int): List<Pair<UUID, Int>> = db.withConnection { c ->
        c.prepareStatement(
            """
            SELECT to_uuid, COUNT(*)::int AS n FROM endorsements
            GROUP BY to_uuid ORDER BY n DESC LIMIT ?
            """.trimIndent()
        ).use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(r.getObject("to_uuid", UUID::class.java) to r.getInt("n")) }
            }
        }
    }
}

class PostgresModeStatsStore(private val db: Database) : ModeStatsStore {

    private fun ResultSet.toRow() = ModeStatsRecord(
        uuid = getObject("uuid", UUID::class.java),
        season = getInt("season"),
        format = MatchFormat.byName(getString("format")),
        rating = getInt("rating"),
        matchesPlayed = getInt("matches_played"),
        wins = getInt("wins"),
        losses = getInt("losses"),
        draws = getInt("draws"),
        playtimeSeconds = getLong("playtime_s"),
        forfeits = getInt("forfeits"),
        currentStreak = getInt("current_streak"),
        bestStreak = getInt("best_streak"),
        peakRating = getInt("peak_rating"),
    )

    override fun get(uuid: UUID, season: Int, format: MatchFormat): ModeStatsRecord? =
        select(uuid, season, format, lock = false)

    override fun getForUpdate(uuid: UUID, season: Int, format: MatchFormat): ModeStatsRecord? =
        select(uuid, season, format, lock = db.inTransaction())

    private fun select(uuid: UUID, season: Int, format: MatchFormat, lock: Boolean): ModeStatsRecord? =
        db.withConnection { c ->
            val sql = "SELECT * FROM mode_stats WHERE uuid = ? AND season = ? AND format = ?" +
                if (lock) " FOR UPDATE" else ""
            c.prepareStatement(sql).use { s ->
                s.setObject(1, uuid)
                s.setInt(2, season)
                s.setString(3, format.name)
                s.executeQuery().use { r -> if (r.next()) r.toRow() else null }
            }
        }

    override fun allFor(uuid: UUID, season: Int): List<ModeStatsRecord> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM mode_stats WHERE uuid = ? AND season = ?").use { s ->
            s.setObject(1, uuid)
            s.setInt(2, season)
            s.executeQuery().use { r -> buildList { while (r.next()) add(r.toRow()) } }
        }
    }

    override fun upsert(record: ModeStatsRecord) {
        db.withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO mode_stats (uuid, season, format, rating, matches_played, wins, losses,
                    draws, playtime_s, forfeits, current_streak, best_streak, peak_rating)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid, season, format) DO UPDATE SET
                    rating = excluded.rating, matches_played = excluded.matches_played,
                    wins = excluded.wins, losses = excluded.losses, draws = excluded.draws,
                    playtime_s = excluded.playtime_s, forfeits = excluded.forfeits,
                    current_streak = excluded.current_streak, best_streak = excluded.best_streak,
                    peak_rating = excluded.peak_rating
                """.trimIndent()
            ).use { s ->
                s.setObject(1, record.uuid)
                s.setInt(2, record.season)
                s.setString(3, record.format.name)
                s.setInt(4, record.rating)
                s.setInt(5, record.matchesPlayed)
                s.setInt(6, record.wins)
                s.setInt(7, record.losses)
                s.setInt(8, record.draws)
                s.setLong(9, record.playtimeSeconds)
                s.setInt(10, record.forfeits)
                s.setInt(11, record.currentStreak)
                s.setInt(12, record.bestStreak)
                s.setInt(13, record.peakRating)
                s.executeUpdate()
            }
        }
    }

    override fun top(season: Int, format: MatchFormat, limit: Int, minMatches: Int): List<ModeStatsRecord> =
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT * FROM mode_stats WHERE season = ? AND format = ? AND matches_played >= ?
                ORDER BY rating DESC LIMIT ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setString(2, format.name)
                s.setInt(3, minMatches)
                s.setInt(4, limit)
                s.executeQuery().use { r -> buildList { while (r.next()) add(r.toRow()) } }
            }
        }

    override fun rankOf(uuid: UUID, season: Int, format: MatchFormat, minMatches: Int): Int? =
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT rank FROM (
                    SELECT uuid, RANK() OVER (ORDER BY rating DESC) AS rank
                    FROM mode_stats WHERE season = ? AND format = ? AND matches_played >= ?
                ) ranked WHERE uuid = ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setString(2, format.name)
                s.setInt(3, minMatches)
                s.setObject(4, uuid)
                s.executeQuery().use { r -> if (r.next()) r.getInt("rank") else null }
            }
        }

    override fun topPlaytime(season: Int, limit: Int): List<Pair<UUID, Long>> =
        aggregate("SUM(playtime_s)::bigint", season, limit) { it.getLong("v") }

    override fun topStreak(season: Int, limit: Int): List<Pair<UUID, Int>> =
        aggregate("MAX(best_streak)::int", season, limit) { it.getInt("v") }

    override fun topWins(season: Int, limit: Int): List<Pair<UUID, Int>> =
        aggregate("SUM(wins)::int", season, limit) { it.getInt("v") }

    /** One cross-mode board. [expression] is server-controlled, never user input. */
    private fun <T> aggregate(
        expression: String,
        season: Int,
        limit: Int,
        read: (ResultSet) -> T,
    ): List<Pair<UUID, T>> = db.withConnection { c ->
        c.prepareStatement(
            """
            SELECT uuid, $expression AS v FROM mode_stats WHERE season = ?
            GROUP BY uuid ORDER BY v DESC LIMIT ?
            """.trimIndent()
        ).use { s ->
            s.setInt(1, season)
            s.setInt(2, limit)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(r.getObject("uuid", UUID::class.java) to read(r)) }
            }
        }
    }
}
