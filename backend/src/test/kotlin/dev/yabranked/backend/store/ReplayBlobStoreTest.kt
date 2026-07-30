package dev.yabranked.backend.store

import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The append protocol, against both implementations.
 *
 * The rule under test is the one the whole upload path rests on: an append is
 * offered at an offset and the store answers with the length it holds, whether or
 * not this chunk was applied. That is what makes a retry from a container which
 * cannot tell whether its last request landed safe rather than corrupting.
 */
class ReplayBlobStoreTest {

    private fun stores(): List<Pair<String, ReplayBlobStore>> = listOf(
        "memory" to InMemoryReplayBlobStore(),
        "file" to FileReplayBlobStore(Files.createTempDirectory("yabranked-blobs")),
    )

    @Test
    fun `sequential appends extend the stream`() {
        for ((name, store) in stores()) {
            val match = UUID.randomUUID()
            assertEquals(3, store.append(match, 0, 0, byteArrayOf(1, 2, 3)), name)
            assertEquals(5, store.append(match, 0, 3, byteArrayOf(4, 5)), name)
            assertEquals(5, store.length(match, 0), name)
            assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), store.read(match, 0, 0, 64), name)
        }
    }

    @Test
    fun `a duplicate chunk is ignored and answered with the real length`() {
        for ((name, store) in stores()) {
            val match = UUID.randomUUID()
            store.append(match, 0, 0, byteArrayOf(1, 2, 3))
            // The retry of a request that timed out: the sender does not know it
            // arrived, and must be able to ask without doubling the stream.
            assertEquals(3, store.append(match, 0, 0, byteArrayOf(1, 2, 3)), name)
            assertEquals(3, store.length(match, 0), name)
        }
    }

    @Test
    fun `a chunk beyond the end is refused rather than leaving a hole`() {
        for ((name, store) in stores()) {
            val match = UUID.randomUUID()
            store.append(match, 0, 0, byteArrayOf(1, 2, 3))
            assertEquals(3, store.append(match, 0, 99, byteArrayOf(9)), name)
            assertEquals(3, store.length(match, 0), name)
        }
    }

    @Test
    fun `reads clamp to what is there`() {
        for ((name, store) in stores()) {
            val match = UUID.randomUUID()
            store.append(match, 0, 0, byteArrayOf(1, 2, 3, 4))
            assertContentEquals(byteArrayOf(3, 4), store.read(match, 0, 2, 64), name)
            // Past the end is empty, not an error: a client downloading a recording
            // that is still being uploaded runs off the end and comes back.
            assertContentEquals(byteArrayOf(), store.read(match, 0, 4, 64), name)
            assertContentEquals(byteArrayOf(), store.read(match, 1, 0, 64), name)
        }
    }

    @Test
    fun `the in-memory store refuses to grow past its cap`() {
        // The cap is what stops an unconfigured deployment killing its own
        // backend: a recording is tens of megabytes and the heap is not.
        val store = InMemoryReplayBlobStore(maxTotalBytes = 100)
        val match = UUID.randomUUID()

        assertEquals(60, store.append(match, 0, 0, ByteArray(60)))
        // Refused, and answered with the unchanged length — which the agent reads
        // as a stale offset, the same answer the retry protocol already handles.
        assertEquals(60, store.append(match, 0, 60, ByteArray(60)))
        assertEquals(60, store.length(match, 0))

        // The cap is across every match, not per stream: one runaway recording
        // must not be able to spend the whole budget twice.
        val other = UUID.randomUUID()
        assertEquals(0, store.append(other, 0, 0, ByteArray(60)))
        // What still fits is still accepted.
        assertEquals(30, store.append(other, 0, 0, ByteArray(30)))
    }

    @Test
    fun `total bytes counts every stream, and delete drops all of them`() {
        for ((name, store) in stores()) {
            val match = UUID.randomUUID()
            store.append(match, 0, 0, ByteArray(10))
            store.append(match, 1, 0, ByteArray(6))
            assertEquals(16, store.totalBytes(match), name)

            store.delete(match)
            assertEquals(0, store.totalBytes(match), name)
            assertEquals(0, store.length(match, 0), name)
            // Idempotent: the sweep may run against a match whose bytes are
            // already gone, and that is not a failure worth reporting.
            store.delete(match)
        }
    }
}
