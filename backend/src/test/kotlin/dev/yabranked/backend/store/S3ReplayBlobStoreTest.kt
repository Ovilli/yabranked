package dev.yabranked.backend.store

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * [S3ReplayBlobStore] against a real S3 endpoint.
 *
 * Skipped unless one is configured, the same bargain `PostgresStoreTest` makes:
 * a test that needs a service it cannot start has to be silent when the service
 * is absent, or it fails for everyone who is not looking at it.
 *
 * ```sh
 * YABRANKED_TEST_S3_ENDPOINT=http://minio:9000 \
 * YABRANKED_TEST_S3_BUCKET=yabranked-replays \
 * YABRANKED_TEST_S3_ACCESS_KEY=... \
 * YABRANKED_TEST_S3_SECRET_KEY=... \
 * ./gradlew :backend:test --tests '*S3ReplayBlobStoreTest*'
 * ```
 *
 * What it is really checking is that the chunk-per-object layout survives a
 * round trip through something that enforces real S3 semantics: that keys sort
 * back into byte order, that ranged reads reassemble across chunk boundaries,
 * and that a duplicate append is refused rather than doubling the stream. None
 * of that can be proven against the in-memory store, which shares none of the
 * implementation.
 */
class S3ReplayBlobStoreTest {

    private fun storeOrSkip(): S3ReplayBlobStore {
        val store = S3ReplayBlobStore.create(
            endpoint = System.getenv("YABRANKED_TEST_S3_ENDPOINT"),
            bucket = System.getenv("YABRANKED_TEST_S3_BUCKET"),
            accessKey = System.getenv("YABRANKED_TEST_S3_ACCESS_KEY"),
            secretKey = System.getenv("YABRANKED_TEST_S3_SECRET_KEY"),
            region = System.getenv("YABRANKED_TEST_S3_REGION") ?: "auto",
        )
        assumeTrue(store != null, "no S3 endpoint configured")
        return store!!
    }

    @Test
    fun `chunks reassemble in order across appends and ranged reads`() {
        val store = storeOrSkip()
        val match = UUID.randomUUID()
        try {
            // Deliberately uneven and more than ten, so the zero-padding is doing
            // real work: unpadded, `10` would sort between `1` and `2` and the
            // stream would come back scrambled.
            val chunks = (0 until 12).map { i -> ByteArray(1000 + i) { (i + it).toByte() } }
            var offset = 0L
            for (chunk in chunks) {
                offset = store.append(match, 0, offset, chunk)
            }
            val whole = chunks.reduce { a, b -> a + b }
            assertEquals(whole.size.toLong(), offset)
            assertEquals(whole.size.toLong(), store.length(match, 0))

            // The whole thing, in one read.
            assertContentEquals(whole, store.read(match, 0, 0, whole.size))

            // A window that starts and ends inside different chunks — the case a
            // client download hits on every page boundary.
            val from = 1500
            val len = 4000
            assertContentEquals(
                whole.copyOfRange(from, from + len),
                store.read(match, 0, from.toLong(), len),
            )

            // Past the end is empty, not an error: a client following a recording
            // that is still uploading runs off the end and comes back.
            assertContentEquals(ByteArray(0), store.read(match, 0, whole.size.toLong(), 100))
        } finally {
            store.delete(match)
        }
    }

    @Test
    fun `a duplicate append is refused and answered with the real length`() {
        val store = storeOrSkip()
        val match = UUID.randomUUID()
        try {
            val chunk = ByteArray(256) { it.toByte() }
            assertEquals(256, store.append(match, 0, 0, chunk))
            // The retry of a request that timed out. It must not double the stream.
            assertEquals(256, store.append(match, 0, 0, chunk))
            assertEquals(256, store.length(match, 0))
            // And an offset past the end writes nothing rather than leaving a hole.
            assertEquals(256, store.append(match, 0, 9999, chunk))
            assertEquals(256, store.length(match, 0))
        } finally {
            store.delete(match)
        }
    }

    @Test
    fun `streams are independent and delete removes the whole match`() {
        val store = storeOrSkip()
        val match = UUID.randomUUID()
        try {
            store.append(match, 0, 0, ByteArray(300))
            store.append(match, 1, 0, ByteArray(700))
            assertEquals(300, store.length(match, 0))
            assertEquals(700, store.length(match, 1))
            assertEquals(1000, store.totalBytes(match))

            store.delete(match)
            assertEquals(0, store.totalBytes(match))
            assertEquals(0, store.length(match, 0))
            assertEquals(0, store.length(match, 1))
        } finally {
            store.delete(match)
        }
    }
}
