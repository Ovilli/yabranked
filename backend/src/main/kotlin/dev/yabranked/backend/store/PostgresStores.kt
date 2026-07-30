package dev.yabranked.backend.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import dev.yabranked.proto.PrivacySettings
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed stores. One [Database] wraps the pool and applies pending
 * schema migrations at startup (see [SchemaMigrator]).
 *
 * URL format: jdbc:postgresql://host:5432/yabranked (user/password separate).
 */
class Database(
    url: String,
    user: String? = null,
    password: String? = null,
    /**
     * Concurrent JDBC connections. Also the ceiling the store dispatcher is
     * sized against — more callers than connections only ever queue inside
     * Hikari, where the wait does not show up as a busy thread.
     */
    poolSize: Int = DEFAULT_POOL_SIZE,
    /** How long a caller waits for a free connection before Hikari gives up. */
    connectionTimeoutMs: Long = DEFAULT_CONNECTION_TIMEOUT_MS,
    /** Tags Hikari's logs and JMX beans so an exhausted pool is identifiable. */
    poolName: String = DEFAULT_POOL_NAME,
) {
    val dataSource: DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            user?.let { username = it }
            password?.let { this.password = it }
            maximumPoolSize = poolSize
            connectionTimeout = connectionTimeoutMs
            this.poolName = poolName
        }
    )

    /**
     * Connection of the transaction the current thread is inside, if any.
     * Store calls made on that thread join it instead of borrowing their own
     * connection — which is what makes [transaction] atomic across stores.
     */
    @PublishedApi
    internal val transactionConnection = ThreadLocal<Connection?>()

    /**
     * Applies whatever schema changes this build carries that the database has
     * not seen. Versioned and recorded — a database that already has the
     * current schema is baselined, not migrated over. See [SchemaMigrator].
     */
    fun migrate() {
        SchemaMigrator(dataSource).migrate()
    }

    /** Closes the pool; the last step of the graceful-shutdown drain. */
    fun close() {
        (dataSource as? HikariDataSource)?.close()
    }

    /**
     * Runs [block] in one transaction; commits on normal return, rolls back on
     * any throw. Nested calls join the outer transaction rather than opening a
     * second one, so a commit only happens at the outermost level.
     *
     * [block] must stay on the calling thread — the connection is bound to it.
     */
    fun <T> transaction(block: () -> T): T {
        if (transactionConnection.get() != null) return block()
        return dataSource.connection.use { connection ->
            connection.autoCommit = false
            transactionConnection.set(connection)
            try {
                val result = block()
                connection.commit()
                result
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw e
            } finally {
                transactionConnection.remove()
                runCatching { connection.autoCommit = true }
            }
        }
    }

    internal inline fun <T> withConnection(block: (Connection) -> T): T {
        val joined = transactionConnection.get()
        return if (joined != null) block(joined) else dataSource.connection.use(block)
    }

    /** True while the calling thread is inside [transaction]. */
    internal fun inTransaction(): Boolean = transactionConnection.get() != null

    companion object {
        const val DEFAULT_POOL_SIZE = 10
        const val DEFAULT_CONNECTION_TIMEOUT_MS = 30_000L
        const val DEFAULT_POOL_NAME = "yabranked-pool"
    }
}

/** [TransactionRunner] backed by real Postgres transactions. */
class PostgresTransactionRunner(private val db: Database) : TransactionRunner {
    override fun <T> transaction(block: () -> T): T = db.transaction(block)
}

private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()

class PostgresPlayerStore(private val db: Database) : PlayerStore {

    private fun ResultSet.toPlayer() = PlayerRecord(
        uuid = getObject("uuid", UUID::class.java),
        name = getString("name"),
        bannedAt = instant("banned_at"),
        createdAt = instant("created_at")!!,
        country = getString("country"),
        background = getString("background") ?: "default",
        hideFlag = getBoolean("hide_flag"),
        hideRating = getBoolean("hide_rating"),
        // A row written before the privacy block existed has none; rebuild it
        // from the two legacy booleans rather than handing back the permissive
        // defaults, which would silently re-expose a flag the player had hidden.
        privacy = getString("privacy")?.let { SocialJson.decodePrivacy(it) }
            ?: PlayerRecord.privacyFromLegacy(getBoolean("hide_flag"), getBoolean("hide_rating")),
    )

    private fun ResultSet.toStats() = SeasonStats(
        uuid = getObject("uuid", UUID::class.java),
        season = getInt("season"),
        rating = getInt("rating"),
        matchesPlayed = getInt("matches_played"),
        wins = getInt("wins"),
        losses = getInt("losses"),
        draws = getInt("draws"),
        playtimeSeconds = getLong("playtime_seconds"),
        forfeits = getInt("forfeits"),
        peakRating = getInt("peak_rating").let { if (it == 0) getInt("rating") else it },
        lastPlayedAt = instant("last_played_at"),
        decayedThrough = instant("decayed_through"),
    )

    override fun getPlayer(uuid: UUID): PlayerRecord? = selectPlayer(uuid, lock = false)

    override fun getPlayerForUpdate(uuid: UUID): PlayerRecord? =
        selectPlayer(uuid, lock = db.inTransaction())

    private fun selectPlayer(uuid: UUID, lock: Boolean): PlayerRecord? = db.withConnection { c ->
        val sql = "SELECT * FROM players WHERE uuid = ?" + if (lock) " FOR UPDATE" else ""
        c.prepareStatement(sql).use { s ->
            s.setObject(1, uuid)
            s.executeQuery().use { r -> if (r.next()) r.toPlayer() else null }
        }
    }

    override fun getPlayers(uuids: Collection<UUID>): Map<UUID, PlayerRecord> {
        val wanted = uuids.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return db.withConnection { c ->
            c.prepareStatement("SELECT * FROM players WHERE uuid = ANY (?)").use { s ->
                s.setArray(1, c.createArrayOf("uuid", wanted.toTypedArray()))
                s.executeQuery().use { r ->
                    buildMap {
                        while (r.next()) r.toPlayer().let { put(it.uuid, it) }
                    }
                }
            }
        }
    }

    override fun upsertPlayer(record: PlayerRecord) {
        db.withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO players (uuid, name, banned_at, created_at, country, background,
                    hide_flag, hide_rating, privacy)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, banned_at = excluded.banned_at,
                    country = excluded.country, background = excluded.background,
                    hide_flag = excluded.hide_flag, hide_rating = excluded.hide_rating,
                    privacy = excluded.privacy
                """.trimIndent()
            ).use { s ->
                s.setObject(1, record.uuid)
                s.setString(2, record.name)
                s.setTimestamp(3, record.bannedAt?.let(Timestamp::from))
                s.setTimestamp(4, Timestamp.from(record.createdAt))
                s.setString(5, record.country)
                s.setString(6, record.background)
                s.setBoolean(7, record.hideFlag)
                s.setBoolean(8, record.hideRating)
                s.setString(9, SocialJson.encodePrivacy(record.privacy))
                s.executeUpdate()
            }
        }
    }

    override fun findByName(name: String): PlayerRecord? = db.withConnection { c ->
        // lower(name) rather than ILIKE: exact match only, and it can use a
        // functional index if the name lookup ever becomes hot.
        c.prepareStatement("SELECT * FROM players WHERE lower(name) = lower(?) LIMIT 1").use { s ->
            s.setString(1, name)
            s.executeQuery().use { r -> if (r.next()) r.toPlayer() else null }
        }
    }

    override fun getStats(uuid: UUID, season: Int): SeasonStats? = selectStats(uuid, season, lock = false)

    override fun getStatsForUpdate(uuid: UUID, season: Int): SeasonStats? =
        selectStats(uuid, season, lock = db.inTransaction())

    private fun selectStats(uuid: UUID, season: Int, lock: Boolean): SeasonStats? = db.withConnection { c ->
        val sql = "SELECT * FROM season_stats WHERE uuid = ? AND season = ?" +
            if (lock) " FOR UPDATE" else ""
        c.prepareStatement(sql).use { s ->
            s.setObject(1, uuid)
            s.setInt(2, season)
            s.executeQuery().use { r -> if (r.next()) r.toStats() else null }
        }
    }

    override fun upsertStats(stats: SeasonStats) {
        db.withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO season_stats
                    (uuid, season, rating, matches_played, wins, losses, draws,
                     playtime_seconds, forfeits, peak_rating, last_played_at, decayed_through)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid, season) DO UPDATE SET
                    rating = excluded.rating, matches_played = excluded.matches_played,
                    wins = excluded.wins, losses = excluded.losses, draws = excluded.draws,
                    playtime_seconds = excluded.playtime_seconds, forfeits = excluded.forfeits,
                    peak_rating = excluded.peak_rating, last_played_at = excluded.last_played_at,
                    decayed_through = excluded.decayed_through
                """.trimIndent()
            ).use { s ->
                s.setObject(1, stats.uuid)
                s.setInt(2, stats.season)
                s.setInt(3, stats.rating)
                s.setInt(4, stats.matchesPlayed)
                s.setInt(5, stats.wins)
                s.setInt(6, stats.losses)
                s.setInt(7, stats.draws)
                s.setLong(8, stats.playtimeSeconds)
                s.setInt(9, stats.forfeits)
                s.setInt(10, stats.peakRating)
                s.setTimestamp(11, stats.lastPlayedAt?.let(Timestamp::from))
                s.setTimestamp(12, stats.decayedThrough?.let(Timestamp::from))
                s.executeUpdate()
            }
        }
    }

    override fun lifetimeStats(uuid: UUID): LifetimeStats = db.withConnection { c ->
        // Aggregated in SQL rather than by reading every row: this runs on every
        // settle, twice. The casts keep SUM's bigint out of the Int fields, and
        // peak_rating defaults to 0 on rows written before it existed, so the
        // live rating guards the maximum the same way toStats does.
        c.prepareStatement(
            """
            SELECT COALESCE(SUM(matches_played), 0)::int AS matches_played,
                   COALESCE(SUM(wins), 0)::int          AS wins,
                   COALESCE(SUM(losses), 0)::int        AS losses,
                   COALESCE(SUM(draws), 0)::int         AS draws,
                   COALESCE(SUM(playtime_seconds), 0)::bigint AS playtime_seconds,
                   COALESCE(SUM(forfeits), 0)::int      AS forfeits,
                   COALESCE(MAX(GREATEST(peak_rating, rating)), 0)::int AS peak_rating
            FROM season_stats WHERE uuid = ?
            """.trimIndent()
        ).use { s ->
            s.setObject(1, uuid)
            s.executeQuery().use { r ->
                if (!r.next()) LifetimeStats.of(uuid, emptyList())
                else LifetimeStats(
                    uuid = uuid,
                    matchesPlayed = r.getInt("matches_played"),
                    wins = r.getInt("wins"),
                    losses = r.getInt("losses"),
                    draws = r.getInt("draws"),
                    playtimeSeconds = r.getLong("playtime_seconds"),
                    forfeits = r.getInt("forfeits"),
                    peakRating = r.getInt("peak_rating"),
                )
            }
        }
    }

    override fun topByRating(season: Int, limit: Int, minMatches: Int): List<SeasonStats> =
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT * FROM season_stats WHERE season = ? AND matches_played >= ?
                ORDER BY rating DESC LIMIT ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setInt(2, minMatches)
                s.setInt(3, limit)
                s.executeQuery().use { r ->
                    buildList { while (r.next()) add(r.toStats()) }
                }
            }
        }

    override fun leaderboard(season: Int, limit: Int, minMatches: Int): List<LadderEntry> =
        db.withConnection { c ->
            // Only `uuid` exists on both sides and it is the join key, so s.* plus
            // the account columns still lets toStats/toPlayer read by name.
            c.prepareStatement(
                """
                SELECT s.*, p.name, p.banned_at, p.created_at, p.country, p.background,
                       p.hide_flag, p.hide_rating
                FROM season_stats s
                LEFT JOIN players p ON p.uuid = s.uuid
                WHERE s.season = ? AND s.matches_played >= ?
                ORDER BY s.rating DESC LIMIT ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setInt(2, minMatches)
                s.setInt(3, limit)
                s.executeQuery().use { r ->
                    buildList {
                        while (r.next()) {
                            // players.name is NOT NULL, so a null one means the
                            // outer join found no account for this stats row
                            add(LadderEntry(r.toStats(), if (r.getString("name") == null) null else r.toPlayer()))
                        }
                    }
                }
            }
        }

    override fun rankOf(uuid: UUID, season: Int, minMatches: Int): Int? = db.withConnection { c ->
        c.prepareStatement(
            """
            SELECT rank FROM (
                SELECT uuid, RANK() OVER (ORDER BY rating DESC) AS rank
                FROM season_stats WHERE season = ? AND matches_played >= ?
            ) ranked WHERE uuid = ?
            """.trimIndent()
        ).use { s ->
            s.setInt(1, season)
            s.setInt(2, minMatches)
            s.setObject(3, uuid)
            s.executeQuery().use { r -> if (r.next()) r.getInt("rank") else null }
        }
    }
}

class PostgresMatchStore(private val db: Database) : MatchStore {

    private fun ResultSet.toMatch() = MatchRecord(
        id = getObject("id", UUID::class.java),
        season = getInt("season"),
        format = MatchFormat.valueOf(getString("format")),
        settings = MatchSettings(
            format = MatchFormat.valueOf(getString("format")),
            worldSeed = getLong("world_seed"),
            cardSeed = getLong("card_seed"),
            timeLimitSeconds = getLong("time_limit_s"),
            sharedWorld = getBoolean("shared_world"),
            sharedSeed = getBoolean("shared_seed"),
            perTeamWorldSeeds = SocialJson.decodeLongs(getString("per_team_world_seeds")),
            perTeamCardSeeds = SocialJson.decodeLongs(getString("per_team_card_seeds")),
            teamCount = getInt("team_count"),
        ),
        playerA = getObject("player_a", UUID::class.java),
        playerB = getObject("player_b", UUID::class.java),
        status = MatchStatus.valueOf(getString("status")),
        serverToken = getString("server_token"),
        serverAddress = getString("server_address"),
        outcome = getString("outcome")?.let(MatchOutcome::valueOf),
        ratingABefore = getInt("rating_a_before"),
        ratingBBefore = getInt("rating_b_before"),
        ratingAAfter = getObject("rating_a_after") as Int?,
        ratingBAfter = getObject("rating_b_after") as Int?,
        durationSeconds = getObject("duration_s")?.let { (it as Number).toLong() },
        teamAScore = getObject("team_a_score") as Int?,
        teamBScore = getObject("team_b_score") as Int?,
        forfeitedBy = getObject("forfeited_by", UUID::class.java),
        createdAt = instant("created_at")!!,
        completedAt = instant("completed_at"),
        teams = SocialJson.decodeTeams(getString("teams")),
        teamScores = SocialJson.decodeInts(getString("team_scores")),
        winningTeam = getObject("winning_team") as Int?,
        partyId = getObject("party_id", UUID::class.java),
        rated = getBoolean("rated"),
    )

    private fun bind(s: java.sql.PreparedStatement, m: MatchRecord) {
        s.setObject(1, m.id)
        s.setInt(2, m.season)
        s.setString(3, m.format.name)
        s.setLong(4, m.settings.worldSeed)
        s.setLong(5, m.settings.cardSeed)
        s.setLong(6, m.settings.timeLimitSeconds)
        s.setObject(7, m.playerA)
        s.setObject(8, m.playerB)
        s.setString(9, m.status.name)
        s.setString(10, m.serverToken)
        s.setString(11, m.serverAddress)
        s.setString(12, m.outcome?.name)
        s.setInt(13, m.ratingABefore)
        s.setInt(14, m.ratingBBefore)
        s.setObject(15, m.ratingAAfter)
        s.setObject(16, m.ratingBAfter)
        s.setObject(17, m.durationSeconds)
        s.setObject(18, m.teamAScore)
        s.setObject(19, m.teamBScore)
        s.setTimestamp(20, Timestamp.from(m.createdAt))
        s.setTimestamp(21, m.completedAt?.let(Timestamp::from))
        s.setObject(22, m.forfeitedBy)
        s.setString(23, SocialJson.encodeTeams(m.teams))
        s.setString(24, SocialJson.encodeInts(m.teamScores))
        s.setObject(25, m.winningTeam)
        s.setObject(26, m.partyId)
        s.setBoolean(27, m.rated)
        s.setBoolean(28, m.settings.sharedWorld)
        s.setBoolean(29, m.settings.sharedSeed)
        s.setString(30, SocialJson.encodeLongs(m.settings.perTeamWorldSeeds))
        s.setString(31, SocialJson.encodeLongs(m.settings.perTeamCardSeeds))
        s.setInt(32, m.settings.teamCount)
        // The flattened roster is what "matches this player was in" searches;
        // player_a/player_b are only each side's first player once teams exist.
        s.setArray(33, s.connection.createArrayOf("uuid", m.participants.toTypedArray()))
    }

    override fun get(id: UUID): MatchRecord? = selectMatch(id, lock = false)

    override fun getForUpdate(id: UUID): MatchRecord? = selectMatch(id, lock = db.inTransaction())

    private fun selectMatch(id: UUID, lock: Boolean): MatchRecord? = db.withConnection { c ->
        val sql = "SELECT * FROM matches WHERE id = ?" + if (lock) " FOR UPDATE" else ""
        c.prepareStatement(sql).use { s ->
            s.setObject(1, id)
            s.executeQuery().use { r -> if (r.next()) r.toMatch() else null }
        }
    }

    override fun insert(record: MatchRecord) {
        db.withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO matches (id, season, format, world_seed, card_seed, time_limit_s,
                    player_a, player_b, status, server_token, server_address, outcome,
                    rating_a_before, rating_b_before, rating_a_after, rating_b_after,
                    duration_s, team_a_score, team_b_score, created_at, completed_at, forfeited_by,
                    teams, team_scores, winning_team, party_id, rated,
                    shared_world, shared_seed, per_team_world_seeds, per_team_card_seeds, team_count,
                    participants)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { s ->
                bind(s, record)
                s.executeUpdate()
            }
        }
    }

    override fun update(record: MatchRecord) {
        db.withConnection { c ->
            c.prepareStatement(
                """
                UPDATE matches SET status = ?, server_address = ?, outcome = ?,
                    rating_a_after = ?, rating_b_after = ?, duration_s = ?,
                    team_a_score = ?, team_b_score = ?, forfeited_by = ?, completed_at = ?,
                    team_scores = ?, winning_team = ?
                WHERE id = ?
                """.trimIndent()
            ).use { s ->
                s.setString(1, record.status.name)
                s.setString(2, record.serverAddress)
                s.setString(3, record.outcome?.name)
                s.setObject(4, record.ratingAAfter)
                s.setObject(5, record.ratingBAfter)
                s.setObject(6, record.durationSeconds)
                s.setObject(7, record.teamAScore)
                s.setObject(8, record.teamBScore)
                s.setObject(9, record.forfeitedBy)
                s.setTimestamp(10, record.completedAt?.let(Timestamp::from))
                s.setString(11, SocialJson.encodeInts(record.teamScores))
                s.setObject(12, record.winningTeam)
                s.setObject(13, record.id)
                s.executeUpdate()
            }
        }
    }

    override fun historyFor(player: UUID, season: Int, limit: Int): List<MatchRecord> =
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT * FROM matches WHERE season = ? AND participants @> ARRAY[?]::uuid[]
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setObject(2, player)
                s.setInt(3, limit)
                s.executeQuery().use { r ->
                    buildList { while (r.next()) add(r.toMatch()) }
                }
            }
        }

    override fun recentDecided(player: UUID, season: Int, limit: Int): List<MatchRecord> =
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT * FROM matches WHERE season = ? AND participants @> ARRAY[?]::uuid[]
                    AND outcome IS NOT NULL AND outcome <> 'VOID'
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setObject(2, player)
                s.setInt(3, limit)
                s.executeQuery().use { r ->
                    buildList { while (r.next()) add(r.toMatch()) }
                }
            }
        }

    override fun between(a: UUID, b: UUID, season: Int, limit: Int): List<MatchRecord> =
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT * FROM matches WHERE season = ?
                    AND participants @> ARRAY[?, ?]::uuid[]
                    AND outcome IS NOT NULL AND outcome <> 'VOID'
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setObject(2, a)
                s.setObject(3, b)
                s.setInt(4, limit)
                s.executeQuery().use { r ->
                    // Both played; head-to-head additionally means they were on
                    // *opposite* sides, which only the rosters can say.
                    buildList { while (r.next()) add(r.toMatch()) }
                        .filter { it.sideOf(a) != it.sideOf(b) }
                }
            }
        }

    override fun unsettled(): List<MatchRecord> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM matches WHERE status IN ('PENDING', 'ACTIVE')").use { s ->
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(r.toMatch()) }
            }
        }
    }

    // The GIN index on participants is what makes this cheap enough to be
    // polled; player_a/player_b are only each side's first player once teams
    // exist, so they cannot answer this question.
    override fun liveFor(player: UUID): MatchRecord? = db.withConnection { c ->
        c.prepareStatement(
            """
            SELECT * FROM matches
             WHERE status IN ('PENDING', 'ACTIVE') AND participants @> ARRAY[?]::uuid[]
             ORDER BY created_at DESC
             LIMIT 1
            """.trimIndent()
        ).use { s ->
            s.setObject(1, player)
            s.executeQuery().use { r -> if (r.next()) r.toMatch() else null }
        }
    }
}

class PostgresReportStore(private val db: Database) : ReportStore {

    override fun insert(record: ReportRecord) {
        db.withConnection { c ->
            c.prepareStatement(
                "INSERT INTO reports (id, match_id, reporter, accused, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)"
            ).use { s ->
                s.setObject(1, record.id)
                s.setObject(2, record.matchId)
                s.setObject(3, record.reporter)
                s.setObject(4, record.accused)
                s.setString(5, record.reason)
                s.setTimestamp(6, Timestamp.from(record.createdAt))
                s.executeUpdate()
            }
        }
    }

    override fun list(limit: Int): List<ReportRecord> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM reports ORDER BY created_at DESC LIMIT ?").use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(
                        ReportRecord(
                            id = r.getObject("id", UUID::class.java),
                            matchId = r.getObject("match_id", UUID::class.java),
                            reporter = r.getObject("reporter", UUID::class.java),
                            accused = r.getObject("accused", UUID::class.java),
                            reason = r.getString("reason"),
                            createdAt = r.instant("created_at")!!,
                        )
                    )
                }
            }
        }
    }

    override fun existsFor(matchId: UUID, reporter: UUID): Boolean = db.withConnection { c ->
        c.prepareStatement("SELECT 1 FROM reports WHERE match_id = ? AND reporter = ?").use { s ->
            s.setObject(1, matchId)
            s.setObject(2, reporter)
            s.executeQuery().use { r -> r.next() }
        }
    }

    override fun existsFor(matchId: UUID, reporter: UUID, accused: UUID): Boolean = db.withConnection { c ->
        c.prepareStatement(
            "SELECT 1 FROM reports WHERE match_id = ? AND reporter = ? AND accused = ?"
        ).use { s ->
            s.setObject(1, matchId)
            s.setObject(2, reporter)
            s.setObject(3, accused)
            s.executeQuery().use { r -> r.next() }
        }
    }

    override fun forMatch(matchId: UUID): List<ReportRecord> = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM reports WHERE match_id = ? ORDER BY created_at").use { s ->
            s.setObject(1, matchId)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(
                        ReportRecord(
                            id = r.getObject("id", UUID::class.java),
                            matchId = r.getObject("match_id", UUID::class.java),
                            reporter = r.getObject("reporter", UUID::class.java),
                            accused = r.getObject("accused", UUID::class.java),
                            reason = r.getString("reason"),
                            createdAt = r.instant("created_at")!!,
                        )
                    )
                }
            }
        }
    }
}

class PostgresAchievementStore(private val db: Database) : AchievementStore {

    override fun earned(uuid: UUID): List<AchievementRecord> = db.withConnection { c ->
        c.prepareStatement(
            "SELECT achievement_id, earned_at FROM player_achievements WHERE uuid = ?"
        ).use { s ->
            s.setObject(1, uuid)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(
                        AchievementRecord(
                            achievementId = r.getString("achievement_id"),
                            earnedAt = r.instant("earned_at")!!,
                        )
                    )
                }
            }
        }
    }

    override fun award(uuid: UUID, achievementId: String, earnedAt: Instant): Boolean =
        db.withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO player_achievements (uuid, achievement_id, earned_at)
                VALUES (?, ?, ?)
                ON CONFLICT (uuid, achievement_id) DO NOTHING
                """.trimIndent()
            ).use { s ->
                s.setObject(1, uuid)
                s.setString(2, achievementId)
                s.setTimestamp(3, Timestamp.from(earnedAt))
                s.executeUpdate() > 0
            }
        }
}

/** Key/value settings; used to persist the current season across restarts. */
class PostgresSettingsStore(private val db: Database) {

    fun get(key: String): String? = db.withConnection { c ->
        c.prepareStatement("SELECT value FROM settings WHERE key = ?").use { s ->
            s.setString(1, key)
            s.executeQuery().use { r -> if (r.next()) r.getString("value") else null }
        }
    }

    fun put(key: String, value: String) {
        db.withConnection { c ->
            c.prepareStatement(
                "INSERT INTO settings (key, value) VALUES (?, ?) ON CONFLICT (key) DO UPDATE SET value = excluded.value"
            ).use { s ->
                s.setString(1, key)
                s.setString(2, value)
                s.executeUpdate()
            }
        }
    }
}
