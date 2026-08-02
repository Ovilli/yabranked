package dev.yabranked.client.replay

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stream format, from the reader's side.
 *
 * These are the cases a recording actually arrives in: whole, cut off mid-frame by
 * a container that died, and written by a recorder this client does not understand.
 * The middle one is not an error condition — it is the ordinary shape of every
 * checkpointed upload — so the reader has to stop cleanly and say how far it got.
 */
class ReplayStreamFileTest {

    private fun write(bytes: ByteArray): Path {
        val path = Files.createTempFile("yabranked-replay", ".yabr")
        Files.write(path, bytes)
        return path
    }

    private fun frame(protocol: Byte, millis: Int, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(9 + payload.size)
            .put(protocol)
            .putInt(millis)
            .putInt(payload.size)
            .put(payload)
            .array()

    private fun header(version: Byte = ReplayProtocol.FORMAT_VERSION) =
        byteArrayOf('Y'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte(), version)

    @Test
    fun `frames are indexed with their protocol and timestamp`() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(9)
        val path = write(
            header() +
                frame(ReplayProtocol.CONFIGURATION, 0, a) +
                frame(ReplayProtocol.PLAY, 1_500, b)
        )
        val stream = ReplayStreamFile.open(path)!!
        stream.use {
            assertEquals(2, it.frameCount)
            assertEquals(ReplayProtocol.CONFIGURATION, it.protocolAt(0))
            assertEquals(ReplayProtocol.PLAY, it.protocolAt(1))
            assertEquals(1_500, it.timeAt(1))
            assertEquals(1_500, it.endMillis)

            val buffer = ByteArray(16)
            assertEquals(3, it.payloadInto(0, buffer))
            assertContentEquals(a, buffer.copyOf(3))
            assertEquals(1, it.payloadInto(1, buffer))
            assertContentEquals(b, buffer.copyOf(1))
        }
    }

    @Test
    fun `a stream cut off mid-frame is readable up to the last whole one`() {
        val whole = header() + frame(ReplayProtocol.PLAY, 100, byteArrayOf(7, 7, 7))
        // A frame header claiming four bytes with only two behind it: exactly what
        // a container killed mid-write leaves, and what every checkpointed upload
        // looks like until the next chunk lands.
        val path = write(whole + byteArrayOf(ReplayProtocol.PLAY, 0, 0, 0, 200.toByte(), 0, 0, 0, 4, 1, 2))

        val stream = ReplayStreamFile.open(path)!!
        stream.use {
            assertEquals(1, it.frameCount, "the half-written frame is not offered as a frame")
            assertEquals(100, it.endMillis)
            assertEquals(whole.size.toLong(), it.usableBytes)
        }
    }

    @Test
    fun `a recording from another format version is refused rather than half-read`() {
        val path = write(header(version = 99) + frame(ReplayProtocol.PLAY, 0, byteArrayOf(1)))
        assertNull(
            ReplayStreamFile.open(path),
            "a newer recorder's frames are not a longer version of these frames",
        )
    }

    @Test
    fun `something that is not a recording is refused`() {
        assertNull(ReplayStreamFile.open(write("not a replay at all".toByteArray())))
        assertNull(ReplayStreamFile.open(Path.of("no", "such", "file.yabr")))
    }

    @Test
    fun `a header with no frames is empty rather than broken`() {
        val stream = ReplayStreamFile.open(write(header()))!!
        stream.use {
            assertTrue(it.isEmpty)
            assertEquals(0, it.endMillis)
        }
    }

    /**
     * The contract with the writer, written out as literals.
     *
     * `:agent`'s `ReplayStream` produces this format and **shares no code with
     * this reader**: `MAGIC`, the format version, the 5-byte file header and the
     * 9-byte frame header are each declared twice, once per module. Nothing
     * fails at build time when they drift — the reader simply returns null for
     * every stream and the player is told the match has no recording.
     *
     * Every other test here builds its fixture from `ReplayProtocol`, so it
     * agrees with whatever that constant has become. This one is deliberately
     * the opposite: change the version and it fails, which is the entire point.
     * `:agent` cannot be built in CI (its YAB api dependency lives in a private
     * registry), so this is the only half of the format CI can defend.
     */
    @Test
    fun `the reader accepts exactly the bytes the agent writes`() {
        val golden = byteArrayOf(
            0x59, 0x41, 0x42, 0x52, // "YABR"
            2, // format version
            2, // frame: protocol PLAY
            0x00, 0x00, 0x01, 0x2C, // millis since start = 300, big-endian
            0x00, 0x00, 0x00, 0x03, // payload length = 3, big-endian
            7, 8, 9, // payload
        )

        val stream = ReplayStreamFile.open(write(golden))
            ?: error("the reader rejected the format the agent writes")
        stream.use {
            assertEquals(1, it.frameCount)
            assertEquals(2.toByte(), it.protocolAt(0))
            assertEquals(300, it.timeAt(0))
            assertEquals(3, it.lengthAt(0))
            val buffer = ByteArray(3)
            assertEquals(3, it.payloadInto(0, buffer))
            assertContentEquals(byteArrayOf(7, 8, 9), buffer)
        }

        assertEquals(2.toByte(), ReplayProtocol.FORMAT_VERSION, "bumping this orphans every recording in existence")
        assertEquals(1.toByte(), ReplayProtocol.CONFIGURATION)
        assertEquals(2.toByte(), ReplayProtocol.PLAY)
    }
}
