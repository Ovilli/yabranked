package dev.yabranked.backend.store

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Postgres [ReplayStore].
 *
 * Rows only — the packets are in a [ReplayBlobStore]. `size_bytes` is a
 * maintained total rather than a computed one so the quota screens never have to
 * ask the filesystem how big a hundred recordings are.
 *
 * The index text is the largest column left, and the list queries do not select
 * it: `summaries`, `savedFor` and `underReview` all read metadata only.
 */
class PostgresReplayStore(private val db: Database) : ReplayStore {

    override fun ensure(matchId: UUID, recordedAt: Instant, expiresAt: Instant, underReview: Boolean) {
        db.withConnection { c ->
            // DO NOTHING, not an upsert: this runs on every appended chunk, and
            // the row it must not disturb is the one describing the same upload.
            c.prepareStatement(
                """
                INSERT INTO replays (match_id, meta, recorded_at, duration_s, under_review, expires_at)
                VALUES (?, '', ?, 0, ?, ?)
                ON CONFLICT (match_id) DO NOTHING
                """
            ).use { s ->
                s.setObject(1, matchId)
                s.setTimestamp(2, Timestamp.from(recordedAt))
                s.setBoolean(3, underReview)
                s.setTimestamp(4, Timestamp.from(expiresAt))
                s.executeUpdate()
            }
        }
    }

    override fun putMeta(matchId: UUID, meta: String, durationSeconds: Long, complete: Boolean) {
        db.withConnection { c ->
            // `under_review` is deliberately untouched: a report may have landed
            // between two checkpoints of the same recording, and the hold it put
            // on the replay outranks anything the container has to say.
            c.prepareStatement(
                "UPDATE replays SET meta = ?, duration_s = ?, complete = ? WHERE match_id = ?"
            ).use { s ->
                s.setString(1, meta)
                s.setLong(2, durationSeconds)
                s.setBoolean(3, complete)
                s.setObject(4, matchId)
                s.executeUpdate()
            }
        }
    }

    override fun setSizeBytes(matchId: UUID, bytes: Long) {
        db.withConnection { c ->
            c.prepareStatement("UPDATE replays SET size_bytes = ? WHERE match_id = ?").use { s ->
                s.setLong(1, bytes)
                s.setObject(2, matchId)
                s.executeUpdate()
            }
        }
    }

    override fun get(matchId: UUID): ReplayRecord? = db.withConnection { c ->
        val base = c.prepareStatement("SELECT * FROM replays WHERE match_id = ?").use { s ->
            s.setObject(1, matchId)
            s.executeQuery().use { r -> if (r.next()) read(r, withMeta = true) else null }
        } ?: return@withConnection null
        base.copy(savedBy = saversOf(matchId))
    }

    override fun summaries(matchIds: Collection<UUID>): Map<UUID, ReplayRecord> {
        val ids = matchIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return db.withConnection { c ->
            c.prepareStatement("$SUMMARY_COLUMNS FROM replays WHERE match_id = ANY (?)").use { s ->
                s.setArray(1, c.createArrayOf("uuid", ids.toTypedArray()))
                s.executeQuery().use { r ->
                    buildMap {
                        while (r.next()) {
                            val record = read(r, withMeta = false)
                            put(record.matchId, record)
                        }
                    }
                }
            }
        }
    }

    override fun savedFor(player: UUID): List<ReplayRecord> = db.withConnection { c ->
        c.prepareStatement(
            """
            SELECT r.match_id, r.recorded_at, r.duration_s, r.under_review, r.expires_at,
                   r.size_bytes, r.complete, length(r.meta) AS meta_len
            FROM replays r
            JOIN replay_saves s ON s.match_id = r.match_id
            WHERE s.uuid = ?
            ORDER BY r.recorded_at DESC
            """
        ).use { s ->
            s.setObject(1, player)
            s.executeQuery().use { r ->
                buildList {
                    while (r.next()) add(read(r, withMeta = false).copy(savedBy = setOf(player)))
                }
            }
        }
    }

    override fun save(matchId: UUID, player: UUID): Boolean = db.withConnection { c ->
        // The FK to replays is what makes "save a replay that does not exist"
        // impossible; the row count is what distinguishes it from a repeat.
        val exists = c.prepareStatement("SELECT 1 FROM replays WHERE match_id = ?").use { s ->
            s.setObject(1, matchId)
            s.executeQuery().use { it.next() }
        }
        if (!exists) false else insertSave(c, matchId, player)
    }

    override fun unsave(matchId: UUID, player: UUID): Boolean = db.withConnection { c ->
        c.prepareStatement("DELETE FROM replay_saves WHERE match_id = ? AND uuid = ?").use { s ->
            s.setObject(1, matchId)
            s.setObject(2, player)
            s.executeUpdate() > 0
        }
    }

    override fun setUnderReview(matchId: UUID, underReview: Boolean) {
        db.withConnection { c ->
            c.prepareStatement("UPDATE replays SET under_review = ? WHERE match_id = ?").use { s ->
                s.setBoolean(1, underReview)
                s.setObject(2, matchId)
                s.executeUpdate()
            }
        }
    }

    override fun underReview(limit: Int): List<ReplayRecord> = db.withConnection { c ->
        c.prepareStatement(
            "$SUMMARY_COLUMNS FROM replays WHERE under_review ORDER BY recorded_at DESC LIMIT ?"
        ).use { s ->
            s.setInt(1, limit)
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(read(r, withMeta = false)) }
            }
        }
    }

    /**
     * Deletes the rows and reports which matches they were, so the caller can
     * delete the packets. Done as a `RETURNING` rather than a select-then-delete
     * because a replay saved between the two would otherwise lose its bytes while
     * keeping its row.
     */
    override fun pruneExpired(now: Instant): List<UUID> = db.withConnection { c ->
        c.prepareStatement(
            """
            DELETE FROM replays r
            WHERE NOT r.under_review
              AND r.expires_at <= ?
              AND NOT EXISTS (SELECT 1 FROM replay_saves s WHERE s.match_id = r.match_id)
            RETURNING r.match_id
            """
        ).use { s ->
            s.setTimestamp(1, Timestamp.from(now))
            s.executeQuery().use { r ->
                buildList { while (r.next()) add(r.getObject("match_id", UUID::class.java)) }
            }
        }
    }

    private fun insertSave(c: java.sql.Connection, matchId: UUID, player: UUID): Boolean =
        c.prepareStatement(
            "INSERT INTO replay_saves (match_id, uuid) VALUES (?, ?) ON CONFLICT DO NOTHING"
        ).use { s ->
            s.setObject(1, matchId)
            s.setObject(2, player)
            s.executeUpdate() > 0
        }

    private fun saversOf(matchId: UUID): Set<UUID> = db.withConnection { c ->
        c.prepareStatement("SELECT uuid FROM replay_saves WHERE match_id = ?").use { s ->
            s.setObject(1, matchId)
            s.executeQuery().use { r ->
                buildSet { while (r.next()) add(r.getObject("uuid", UUID::class.java)) }
            }
        }
    }

    /**
     * [withMeta] false means the row came from a summary query, which does not
     * select the index text. `playable` still has to be answerable from such a
     * row — a list has to know which of its entries can be opened — so the
     * summary queries select `length(meta)` and this substitutes a placeholder.
     */
    private fun read(r: ResultSet, withMeta: Boolean): ReplayRecord {
        val meta = if (withMeta) r.getString("meta") else if (r.getLong("meta_len") > 0) PLACEHOLDER else ""
        return ReplayRecord(
            matchId = r.getObject("match_id", UUID::class.java),
            meta = meta,
            recordedAt = r.getTimestamp("recorded_at").toInstant(),
            durationSeconds = r.getLong("duration_s"),
            underReview = r.getBoolean("under_review"),
            expiresAt = r.getTimestamp("expires_at").toInstant(),
            sizeBytes = r.getLong("size_bytes"),
            complete = r.getBoolean("complete"),
        )
    }

    private companion object {
        const val SUMMARY_COLUMNS =
            "SELECT match_id, recorded_at, duration_s, under_review, expires_at, size_bytes, complete, " +
                "length(meta) AS meta_len"

        /** Stands in for an index that exists but was not selected. Never served. */
        const val PLACEHOLDER = "{}"
    }
}
