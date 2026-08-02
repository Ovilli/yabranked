package dev.yabranked.agent

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The on-disk shape of one recorded stream.
 *
 * This is a format with two independent implementations — this writer, and
 * `ReplayStreamFile` in `:client` which reads it — and **they share no code**.
 * `MAGIC`, `FORMAT_VERSION`, the 5-byte file header and the 9-byte frame header
 * are each declared twice, once per module. Nothing fails at build time if they
 * drift; what happens instead is that the reader rejects every stream and the
 * player is told there is no recording.
 *
 * So the constants are asserted here as literals rather than against the
 * writer's own names. A test that says `assertEquals(MAGIC, MAGIC)` cannot
 * notice the thing that actually goes wrong.
 */
class ReplayStreamTest {

    private val log = LoggerFactory.getLogger(ReplayStreamTest::class.java)
    private val player = AgentConfig.ExpectedPlayer(UUID.randomUUID(), "Anna")
    private lateinit var dir: Path

    private fun stream(
        maxBytes: Long = 1 shl 20,
        startMs: Long = System.currentTimeMillis(),
    ): ReplayStream {
        if (!::dir.isInitialized) dir = Files.createTempDirectory("yabr-test")
        return ReplayStream(
            index = 0,
            player = player,
            team = 0,
            file = dir.resolve("stream-${UUID.randomUUID()}.yabr"),
            recordingStartMs = startMs,
            maxBytes = maxBytes,
            log = log,
        )
    }

    @AfterTest
    fun cleanup() {
        if (::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    @Test
    fun `the file header is YABR and version 2, byte for byte`() {
        // The reader in :client checks exactly these five bytes and returns null
        // — "no recording" — for anything else.
        val s = stream()
        s.close()
        assertContentEquals(byteArrayOf(0x59, 0x41, 0x42, 0x52, 2), s.file.readBytes())
    }

    @Test
    fun `a frame is u8 protocol, u32 millis, u32 length, then the payload`() {
        val s = stream()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        s.append(protocol = 2, payload = payload)
        s.close()

        val bytes = s.file.readBytes()
        assertEquals(5 + 9 + payload.size, bytes.size, "header + frame header + payload")

        val frame = ByteBuffer.wrap(bytes, 5, bytes.size - 5)
        assertEquals(2.toByte(), frame.get(), "protocol is per frame, not a header field")
        val millis = frame.int
        assertTrue(millis >= 0, "millis since the recording start is never negative")
        assertEquals(payload.size, frame.int)
        val read = ByteArray(payload.size).also { frame.get(it) }
        assertContentEquals(payload, read)
    }

    @Test
    fun `the length field is big-endian, which is what the reader assumes`() {
        // ByteBuffer defaults to big-endian and the reader uses DataInput, which
        // is big-endian by specification. A writer switched to little-endian
        // would still produce a file, and every frame in it would be garbage.
        val s = stream()
        s.append(protocol = 1, payload = ByteArray(258))
        s.close()

        val bytes = s.file.readBytes()
        // length occupies bytes 10..13 of the file (5 header + 1 protocol + 4 millis)
        assertContentEquals(byteArrayOf(0, 0, 1, 2), bytes.copyOfRange(10, 14))
    }

    @Test
    fun `size and packet count track what was handed over`() {
        val s = stream()
        assertEquals(5L, s.sizeBytes, "the header counts against the quota too")
        assertEquals(0L, s.packetCount)

        s.append(1, ByteArray(10))
        s.append(2, ByteArray(20))

        assertEquals(5L + (9 + 10) + (9 + 20), s.sizeBytes)
        assertEquals(2L, s.packetCount)
        s.close()
    }

    @Test
    fun `timestamps are relative to the recording start, not the epoch`() {
        // t=0 of a recording is BEFORE_CONFIGURE, which is earlier than the
        // match; the client anchors the playhead on it.
        val s = stream(startMs = System.currentTimeMillis() - 5_000)
        s.append(2, byteArrayOf(0))
        s.close()

        val frame = ByteBuffer.wrap(s.file.readBytes(), 5, 9)
        frame.get()
        val millis = frame.int
        assertTrue(millis in 4_000..600_000, "expected roughly 5s since start, got ${millis}ms")
        assertTrue(s.firstMillis >= 4_000)
    }

    @Test
    fun `hitting the cap truncates and stops, rather than growing past it`() {
        // The cap is what stops one match filling the host's disk. Past it the
        // stream reports `truncated`, which the client shows rather than hides.
        val s = stream(maxBytes = 64)
        s.append(1, ByteArray(20))
        assertFalse(s.truncated)

        s.append(1, ByteArray(100))

        assertTrue(s.truncated, "a frame that would exceed the cap must truncate")
        assertTrue(s.sizeBytes <= 64, "size ${s.sizeBytes} went past the 64 byte cap")
        val before = s.sizeBytes
        s.append(1, ByteArray(1))
        assertEquals(before, s.sizeBytes, "a truncated stream accepts nothing further")
    }

    @Test
    fun `a truncated stream is still a valid file up to where it stopped`() {
        val s = stream(maxBytes = 64)
        s.append(1, ByteArray(20))
        s.append(1, ByteArray(100))

        val bytes = s.file.readBytes()
        assertContentEquals(byteArrayOf(0x59, 0x41, 0x42, 0x52, 2), bytes.copyOfRange(0, 5))
        assertEquals(5 + 9 + 20, bytes.size, "the oversized frame must not be half-written")
    }

    @Test
    fun `readFrom flushes, so the uploader sees everything sizeBytes claims`() {
        // Writes come from the Netty event loop and reads from the uploader
        // thread; an unflushed buffer would have the upload skip bytes it had
        // already counted, and offsets after it would all be wrong.
        val s = stream()
        s.append(2, ByteArray(32) { it.toByte() })

        val all = s.readFrom(0, Int.MAX_VALUE)
        assertEquals(s.sizeBytes, all.size.toLong())

        val tail = s.readFrom(5, Int.MAX_VALUE)
        assertContentEquals(all.copyOfRange(5, all.size), tail)
        s.close()
    }

    @Test
    fun `a read past the end is empty rather than an error`() {
        // The uploader asks for the tail on a timer; "nothing new" is the
        // ordinary answer, not a failure.
        val s = stream()
        s.append(2, ByteArray(4))
        assertTrue(s.readFrom(s.sizeBytes, 1024).isEmpty())
        assertTrue(s.readFrom(s.sizeBytes + 1000, 1024).isEmpty())
        s.close()
    }

    @Test
    fun `a closed stream accepts nothing and keeps what it had`() {
        val s = stream()
        s.append(2, ByteArray(8))
        val size = s.sizeBytes
        s.close()

        s.append(2, ByteArray(8))

        assertEquals(size, s.sizeBytes)
        assertFalse(s.truncated, "closing normally is not a truncation")
    }

    @Test
    fun `the protocol codes are the ones the reader decodes`() {
        // Declared independently in :client as well. A renumbering here turns
        // every configuration frame into a play frame, which is a malformed
        // packet in the middle of an already-drawn world.
        assertEquals(0.toByte(), ReplayFormat.PROTOCOL_SKIP)
        assertEquals(1.toByte(), ReplayFormat.PROTOCOL_CONFIGURATION)
        assertEquals(2.toByte(), ReplayFormat.PROTOCOL_PLAY)
    }

    @Test
    fun `the header sizes match the layout they describe`() {
        // Both are used as offsets by the reader; either being wrong shifts
        // every frame in the file by a few bytes and decodes noise.
        assertEquals(5, ReplayFormat.FILE_HEADER, "\"YABR\" plus one version byte")
        assertEquals(9, ReplayFormat.FRAME_HEADER, "u8 protocol + u32 millis + u32 length")
        assertEquals(4, ReplayFormat.MAGIC.size)
    }
}
