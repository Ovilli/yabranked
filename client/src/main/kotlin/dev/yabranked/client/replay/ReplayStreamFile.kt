package dev.yabranked.client.replay

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * One downloaded packet stream, indexed for seeking.
 *
 * The file is what the agent's tap wrote: four magic bytes, a format version, and
 * then frames of `u8 protocol | u32 millisSinceStart | u32 length | payload`. This
 * scans it once to build the index — protocol, timestamp and where the payload is
 * — and then reads payloads on demand. The index for a 20-minute stream is a few
 * hundred thousand entries of three primitives, which is a couple of megabytes;
 * the stream itself is fifty, and is never held in memory.
 *
 * A truncated file is not an error. It is the ordinary shape of a recording whose
 * container died mid-match, and of one that is still being uploaded — so the scan
 * stops at the last frame that is entirely present and reports how far it got,
 * rather than refusing a match somebody wants to watch the first ten minutes of.
 */
class ReplayStreamFile private constructor(
    val path: Path,
    /** [ReplayProtocol] code per frame, parallel to [times]. */
    private val protocols: ByteArray,
    /** Millis from the recording's start, per frame. Non-decreasing. */
    private val times: IntArray,
    private val offsets: LongArray,
    private val lengths: IntArray,
    val frameCount: Int,
    /** Bytes of the file that parsed as whole frames. */
    val usableBytes: Long,
) : AutoCloseable {
    private val file = RandomAccessFile(path.toFile(), "r")

    val isEmpty: Boolean get() = frameCount == 0

    /** Millis of the last frame, i.e. how far playback can get. */
    val endMillis: Int get() = if (frameCount == 0) 0 else times[frameCount - 1]

    fun protocolAt(frame: Int): Byte = protocols[frame]

    fun timeAt(frame: Int): Int = times[frame]

    fun lengthAt(frame: Int): Int = lengths[frame]

    /** Read one frame's payload into [into], returning how many bytes it is. */
    fun payloadInto(frame: Int, into: ByteArray): Int {
        val length = lengths[frame]
        require(into.size >= length) { "buffer of ${into.size} for a $length byte frame" }
        file.seek(offsets[frame])
        file.readFully(into, 0, length)
        return length
    }

    override fun close() {
        runCatching { file.close() }
    }

    companion object {
        /** `u8 protocol | u32 millis | u32 length`. */
        private const val FRAME_HEADER = 9
        private const val HEADER = 5
        private val MAGIC = byteArrayOf('Y'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte())

        /**
         * Index [path], or null when it is not a stream this client can read —
         * wrong magic, or a format version from a recorder newer than this mod.
         */
        fun open(path: Path): ReplayStreamFile? {
            if (!Files.isRegularFile(path)) return null
            val size = Files.size(path)
            if (size < HEADER) return null

            var frames = 0
            val protocols = ByteArrayList()
            val times = IntArrayList()
            val offsets = LongArrayList()
            val lengths = IntArrayList()
            var usable = HEADER.toLong()

            RandomAccessFile(path.toFile(), "r").use { file ->
                val magic = ByteArray(4)
                file.readFully(magic)
                if (!magic.contentEquals(MAGIC)) return null
                val version = file.readByte()
                if (version != ReplayProtocol.FORMAT_VERSION) return null

                val header = ByteArray(FRAME_HEADER)
                var at = HEADER.toLong()
                while (at + FRAME_HEADER <= size) {
                    file.seek(at)
                    file.readFully(header)
                    val protocol = header[0]
                    val millis = readInt(header, 1)
                    val length = readInt(header, 5)
                    // A negative or absurd length means the file is damaged rather
                    // than merely short; stopping is the same answer either way.
                    if (length < 0 || at + FRAME_HEADER + length > size) break
                    protocols.add(protocol)
                    times.add(millis)
                    offsets.add(at + FRAME_HEADER)
                    lengths.add(length)
                    frames++
                    at += FRAME_HEADER + length
                    usable = at
                }
            }

            return ReplayStreamFile(
                path = path,
                protocols = protocols.toArray(),
                times = times.toArray(),
                offsets = offsets.toArray(),
                lengths = lengths.toArray(),
                frameCount = frames,
                usableBytes = usable,
            )
        }

        private fun readInt(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 0xFF) shl 24) or
                ((bytes[at + 1].toInt() and 0xFF) shl 16) or
                ((bytes[at + 2].toInt() and 0xFF) shl 8) or
                (bytes[at + 3].toInt() and 0xFF)
    }
}

/** Frame protocol codes, as the agent's tap writes them. */
object ReplayProtocol {
    const val FORMAT_VERSION: Byte = 2
    const val CONFIGURATION: Byte = 1
    const val PLAY: Byte = 2
}

/*
 * Growable primitive lists.
 *
 * A stream has a few hundred thousand frames and four parallel arrays describing
 * them, so `ArrayList<Int>` here would box a million values during a scan that
 * happens while the player is waiting to watch something. These exist only for
 * that scan.
 */

private class ByteArrayList {
    private var data = ByteArray(1 shl 12)
    private var size = 0
    fun add(value: Byte) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }
    fun toArray(): ByteArray = data.copyOf(size)
}

private class IntArrayList {
    private var data = IntArray(1 shl 12)
    private var size = 0
    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }
    fun toArray(): IntArray = data.copyOf(size)
}

private class LongArrayList {
    private var data = LongArray(1 shl 12)
    private var size = 0
    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = value
    }
    fun toArray(): LongArray = data.copyOf(size)
}
