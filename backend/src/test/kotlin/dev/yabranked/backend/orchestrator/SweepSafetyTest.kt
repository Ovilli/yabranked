package dev.yabranked.backend.orchestrator

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The orphan sweep must not delete containers it did not create.
 *
 * This is not hypothetical. The prefix was `yabranked-`, the sweep removes
 * everything it matches, and a hand-run `yabranked-postgres` on the same host was
 * destroyed twice — the backend deleting its own database — before the pattern
 * was recognised. Two things guard it now: a prefix specific to match servers,
 * and a suffix that has to parse as a match id.
 */
class SweepSafetyTest {

    private val prefix = MatchOrchestrator.CONTAINER_PREFIX

    /** Mirrors `MatchOrchestrator.looksLikeMatchContainer`, which is private. */
    private fun claimed(name: String): Boolean =
        name.startsWith(prefix) &&
            runCatching { java.util.UUID.fromString(name.removePrefix(prefix)) }.isSuccess

    @Test
    fun `claims its own match containers`() {
        val id = java.util.UUID.randomUUID()
        assertTrue(claimed("$prefix$id"))
    }

    @Test
    fun `does not claim other containers that merely share the project name`() {
        for (name in listOf(
            "yabranked-postgres",
            "yabranked-minio",
            "yabranked-backend",
            "yabranked-match-postgres",
            "${prefix}not-a-uuid",
            "$prefix",
            "postgres",
        )) {
            assertFalse(claimed(name), "$name must not be swept")
        }
    }

    @Test
    fun `the prefix is specific enough to not collide with sibling services`() {
        // "yabranked-" would match a database, an object store, anything named
        // for the project. The point of the longer prefix is that it cannot.
        assertTrue(prefix.startsWith("yabranked-"))
        assertTrue(prefix.length > "yabranked-".length, "prefix must be narrower than the project name")
    }
}
