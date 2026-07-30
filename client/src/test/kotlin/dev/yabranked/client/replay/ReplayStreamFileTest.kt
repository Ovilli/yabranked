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
}
