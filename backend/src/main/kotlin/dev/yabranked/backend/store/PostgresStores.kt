package dev.yabranked.backend.store

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * Postgres-backed stores. One [Database] wraps the pool and runs schema.sql
 * (idempotent CREATE IF NOT EXISTS) at startup.
 *
 * URL format: jdbc:postgresql://host:5432/yabranked (user/password separate).
 */
class Database(url: String, user: String?, password: String?) {
    val dataSource: DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            user?.let { username = it }
            password?.let { this.password = it }
            maximumPoolSize = 10
        }
    )

    fun migrate() {
        val schema = requireNotNull(javaClass.getResourceAsStream("/schema.sql")) {
            "schema.sql missing from resources"
        }.bufferedReader().readText()

        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(schema)
            }
        }
    }

    internal inline fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)
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
    )

    override fun getPlayer(uuid: UUID): PlayerRecord? = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM players WHERE uuid = ?").use { s ->
            s.setObject(1, uuid)
            s.executeQuery().use { r -> if (r.next()) r.toPlayer() else null }
        }
    }

    override fun upsertPlayer(record: PlayerRecord) {
        db.withConnection { c ->
            c.prepareStatement(
                """
                INSERT INTO players (uuid, name, banned_at, created_at, country, background)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, banned_at = excluded.banned_at,
                    country = excluded.country, background = excluded.background
                """.trimIndent()
            ).use { s ->
                s.setObject(1, record.uuid)
                s.setString(2, record.name)
                s.setTimestamp(3, record.bannedAt?.let(Timestamp::from))
                s.setTimestamp(4, Timestamp.from(record.createdAt))
                s.setString(5, record.country)
                s.setString(6, record.background)
                s.executeUpdate()
            }
        }
    }

    override fun getStats(uuid: UUID, season: Int): SeasonStats? = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM season_stats WHERE uuid = ? AND season = ?").use { s ->
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
                    (uuid, season, rating, matches_played, wins, losses, draws, playtime_seconds, forfeits)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (uuid, season) DO UPDATE SET
                    rating = excluded.rating, matches_played = excluded.matches_played,
                    wins = excluded.wins, losses = excluded.losses, draws = excluded.draws,
                    playtime_seconds = excluded.playtime_seconds, forfeits = excluded.forfeits
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
                s.executeUpdate()
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
    }

    override fun get(id: UUID): MatchRecord? = db.withConnection { c ->
        c.prepareStatement("SELECT * FROM matches WHERE id = ?").use { s ->
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
                    duration_s, team_a_score, team_b_score, created_at, completed_at, forfeited_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    team_a_score = ?, team_b_score = ?, forfeited_by = ?, completed_at = ?
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
                s.setObject(11, record.id)
                s.executeUpdate()
            }
        }
    }

    override fun historyFor(player: UUID, season: Int, limit: Int): List<MatchRecord> =
        db.withConnection { c ->
            c.prepareStatement(
                """
                SELECT * FROM matches WHERE season = ? AND (player_a = ? OR player_b = ?)
                ORDER BY created_at DESC LIMIT ?
                """.trimIndent()
            ).use { s ->
                s.setInt(1, season)
                s.setObject(2, player)
                s.setObject(3, player)
                s.setInt(4, limit)
                s.executeQuery().use { r ->
                    buildList { while (r.next()) add(r.toMatch()) }
                }
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
