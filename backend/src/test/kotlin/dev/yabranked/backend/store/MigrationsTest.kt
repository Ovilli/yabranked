package dev.yabranked.backend.store

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Migrations are the one part of the schema that cannot be checked by running
 * it here — `PostgresStoreTest` skips without Docker. These tests cover the
 * parts that are checkable off a live database: that every registered
 * migration actually exists, and that the plan does the right thing on a
 * database predating versioning.
 */
class MigrationsTest {

    private fun migration(version: Int) = Migration(version, "m$version", "/schema.sql")

    @Test
    fun `every registered migration resolves to a readable resource`() {
        for (migration in SchemaMigrator.ALL) {
            val sql = migration.sql()
            assertTrue(
                sql.isNotBlank(),
                "migration ${migration.version} (${migration.name}) resolved to an empty file",
            )
        }
    }

    @Test
    fun `a missing migration file fails loudly rather than silently doing nothing`() {
        val missing = Migration(99, "nope", "/migrations/does-not-exist.sql")

        assertFailsWith<IllegalArgumentException> { missing.sql() }
    }

    @Test
    fun `versions are unique and ordered`() {
        val versions = SchemaMigrator.ALL.map { it.version }

        assertEquals(versions.distinct(), versions, "duplicate migration version")
        assertEquals(versions.sorted(), versions, "migrations are not in ascending order")
    }

    @Test
    fun `a pre-versioning database is baselined, not re-migrated`() {
        val plan = SchemaMigrator.plan(
            migrations = listOf(migration(1), migration(2)),
            applied = emptySet(),
            preVersioning = true,
        )

        // Re-running the baseline over a schema that already has it is exactly
        // what versioning exists to avoid.
        assertEquals(listOf(1), plan.baseline.map { it.version })
        assertEquals(listOf(2), plan.run.map { it.version })
    }

    @Test
    fun `an empty database runs every migration including the baseline`() {
        val plan = SchemaMigrator.plan(
            migrations = listOf(migration(1), migration(2)),
            applied = emptySet(),
            preVersioning = false,
        )

        assertTrue(plan.baseline.isEmpty())
        assertEquals(listOf(1, 2), plan.run.map { it.version })
    }

    @Test
    fun `already applied migrations are not run again`() {
        val plan = SchemaMigrator.plan(
            migrations = listOf(migration(1), migration(2), migration(3)),
            applied = setOf(1, 2),
            preVersioning = false,
        )

        assertEquals(listOf(3), plan.run.map { it.version })
    }

    @Test
    fun `duplicate versions are rejected`() {
        assertFailsWith<IllegalStateException> {
            SchemaMigrator.plan(
                migrations = listOf(migration(1), migration(1)),
                applied = emptySet(),
                preVersioning = false,
            )
        }
    }
}
