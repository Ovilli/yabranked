package dev.yabranked.backend.store

import org.slf4j.LoggerFactory
import javax.sql.DataSource

/** One versioned schema change; [resource] is a classpath SQL file. */
data class Migration(val version: Int, val name: String, val resource: String) {
    fun sql(): String = requireNotNull(Migration::class.java.getResourceAsStream(resource)) {
        "migration $version ($name): $resource missing from resources"
    }.bufferedReader().readText()
}

/**
 * Ordered, recorded schema migrations.
 *
 * `schema.sql` used to be executed whole on every startup, which is why its
 * convention was to append `ALTER TABLE … ADD COLUMN IF NOT EXISTS` forever.
 * It is now migration 1 — the baseline — and every later change gets its own
 * file under `resources/migrations/` plus an entry in [ALL]; nothing is ever
 * appended to the baseline again.
 *
 * A database that already has the schema but no `schema_version` table
 * predates versioning. Re-running the baseline over it would be wasteful at
 * best and destructive once the baseline stops being written defensively, so
 * it is *recorded* as being at the baseline instead of migrated onto it (this
 * is what Flyway calls baselining). Later migrations still run against it.
 */
class SchemaMigrator(
    private val dataSource: DataSource,
    private val migrations: List<Migration> = ALL,
) {
    private val log = LoggerFactory.getLogger("migrations")

    fun migrate() {
        val plan = dataSource.connection.use { connection ->
            connection.createStatement().use { it.execute(CREATE_VERSION_TABLE) }
            // One migrator at a time: two backends starting together would
            // otherwise both see the same empty version table and race.
            connection.createStatement().use { it.execute("SELECT pg_advisory_lock($LOCK_KEY)") }
            try {
                plan(migrations, appliedVersions(connection), isPreVersioningDatabase(connection))
            } finally {
                connection.createStatement().use { it.execute("SELECT pg_advisory_unlock($LOCK_KEY)") }
            }
        }

        // The advisory lock is released above rather than held across the
        // apply: the lock is session-scoped and each migration needs its own
        // connection to get its own transaction. Concurrent starts are rare
        // enough that the recorded version, checked per migration, is enough.
        for (migration in plan.baseline) {
            log.info("baselining at migration {} ({}): schema already present", migration.version, migration.name)
            record(migration)
        }
        for (migration in plan.run) {
            log.info("applying migration {} ({})", migration.version, migration.name)
            apply(migration)
        }
        if (plan.run.isEmpty() && plan.baseline.isEmpty()) log.info("schema up to date")
    }

    /** Whole file plus the version row in one transaction: a half-applied migration is not a state. */
    private fun apply(migration: Migration) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute(migration.sql()) }
                insertVersion(connection, migration)
                connection.commit()
            } catch (e: Throwable) {
                runCatching { connection.rollback() }
                throw IllegalStateException("migration ${migration.version} (${migration.name}) failed", e)
            } finally {
                runCatching { connection.autoCommit = true }
            }
        }
    }

    private fun record(migration: Migration) {
        dataSource.connection.use { insertVersion(it, migration) }
    }

    private fun insertVersion(connection: java.sql.Connection, migration: Migration) {
        connection.prepareStatement(
            "INSERT INTO schema_version (version, name) VALUES (?, ?) ON CONFLICT (version) DO NOTHING"
        ).use {
            it.setInt(1, migration.version)
            it.setString(2, migration.name)
            it.executeUpdate()
        }
    }

    private fun appliedVersions(connection: java.sql.Connection): Set<Int> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT version FROM schema_version").use { rows ->
                buildSet { while (rows.next()) add(rows.getInt(1)) }
            }
        }

    /** A schema created before versioning existed: tables present, nothing recorded. */
    private fun isPreVersioningDatabase(connection: java.sql.Connection): Boolean =
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                    WHERE table_schema = current_schema() AND table_name = 'players'
                )
                """.trimIndent()
            ).use { rows -> rows.next() && rows.getBoolean(1) }
        }

    /** What [migrate] decided to do; [baseline] is recorded only, [run] is executed. */
    internal data class Plan(val baseline: List<Migration>, val run: List<Migration>)

    companion object {
        /**
         * Every migration, in order. Append here and drop the file in
         * `resources/migrations/` — never edit a migration that has shipped.
         */
        val ALL = listOf(
            Migration(1, "baseline", "/schema.sql"),
            Migration(2, "rating_decay", "/migrations/V2__rating_decay.sql"),
            Migration(3, "social", "/migrations/V3__social.sql"),
            Migration(4, "replays", "/migrations/V4__replays.sql"),
            Migration(5, "replay_packet_capture", "/migrations/V5__replay_packet_capture.sql"),
            Migration(6, "report_resolution", "/migrations/V6__report_resolution.sql"),
        )

        private const val CREATE_VERSION_TABLE = """
            CREATE TABLE IF NOT EXISTS schema_version (
                version     integer PRIMARY KEY,
                name        text NOT NULL,
                applied_at  timestamptz NOT NULL DEFAULT now()
            )
        """

        /** Arbitrary but fixed: only other yabranked backends take this lock. */
        private const val LOCK_KEY = 8_310_251L

        internal fun plan(
            migrations: List<Migration>,
            applied: Set<Int>,
            preVersioning: Boolean,
        ): Plan {
            val ordered = migrations.sortedBy { it.version }
            val duplicate = ordered.groupBy { it.version }.entries.firstOrNull { it.value.size > 1 }
            check(duplicate == null) { "duplicate migration version ${duplicate?.key}" }

            val pending = ordered.filter { it.version !in applied }
            // Only the baseline describes the pre-versioning schema; anything
            // after it is a change that database has genuinely never seen.
            val baseline = if (preVersioning) pending.filter { it.version == BASELINE_VERSION } else emptyList()
            return Plan(baseline = baseline, run = pending - baseline.toSet())
        }

        internal const val BASELINE_VERSION = 1
    }
}
