package dev.yabranked.agent

import org.slf4j.Logger
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

/**
 * The `.yabr` container format, as written.
 *
 * **There is a second implementation of this and it shares no code with this
 * one**: `ReplayStreamFile` in `:client` reads what is written here, and
 * declares the magic, the version and both header sizes all over again. Nothing
 * fails at build time when the two drift — the reader simply rejects every
 * stream, and the player is told the match has no recording. Both sides are
 * pinned by tests that assert the bytes as *literals* rather than against their
 * own constants, because a fixture built from the constant agrees with whatever
 * the constant became.
 *
 * Unifying them properly means putting this in `:proto`, which the client
 * already flattens; `:agent` does not depend on `:proto` today.
 */
object ReplayFormat {
    /** File header: magic plus the format version the frames are written in. */
    val MAGIC = byteArrayOf('Y'.code.toByte(), 'A'.code.toByte(), 'B'.code.toByte(), 'R'.code.toByte())
    const val FORMAT_VERSION: Byte = 2

    /** `"YABR"` plus the version byte. */
    const val FILE_HEADER = 5

    /** `u8 protocol | u32 millis | u32 length`. */
    const val FRAME_HEADER = 9

    /** Not recorded: the frame belongs to a phase a viewer never replays. */
    const val PROTOCOL_SKIP: Byte = 0
    const val PROTOCOL_CONFIGURATION: Byte = 1
    const val PROTOCOL_PLAY: Byte = 2
}

/**
 * One player's stream, on disk.
 *
 * Layout: the four magic bytes and a version byte, then frames of
 * `u8 protocol | u32 millisSinceStart | u32 length | payload`. The protocol is
 * stored per frame rather than as a header field because a connection changes
 * protocol mid-stream; the timestamp is millis rather than ticks because the
 * recording has no ticks of its own, only the wall clock the packets arrived on.
 *
 * Writes come from the connection's Netty event loop and reads from the uploader
 * thread, so both go through the same monitor. The buffer is flushed by the
 * reader rather than on a timer: the only thing that ever needs the bytes to be
 * on disk is something about to read them.
 *
 * Deliberately free of Minecraft and YAB types, so it can be tested on a machine
 * that has neither — see the module comment in `agent-core/build.gradle.kts`.
 */
class ReplayStream(
    val index: Int,
    val player: AgentConfig.ExpectedPlayer,
    val team: Int,
    val file: Path,
    private val recordingStartMs: Long,
    private val maxBytes: Long,
    private val log: Logger,
) {
    private val out = BufferedOutputStream(FileOutputStream(file.toFile(), true), 64 * 1024)
    private val header = ByteBuffer.allocate(ReplayFormat.FRAME_HEADER)

    /** Bytes handed to the stream, flushed or not. What the quota is spent on. */
    private val written = AtomicLong(0)
    private val packets = AtomicLong(0)

    @Volatile private var closed = false
    @Volatile var truncated = false
        private set
    @Volatile var firstMillis = -1L
        private set
    @Volatile var lastMillis = 0L
        private set

    val sizeBytes: Long get() = written.get()
    val packetCount: Long get() = packets.get()

    init {
        synchronized(this) {
            out.write(ReplayFormat.MAGIC)
            out.write(ReplayFormat.FORMAT_VERSION.toInt())
        }
        written.addAndGet(ReplayFormat.MAGIC.size + 1L)
    }

    fun append(protocol: Byte, payload: ByteArray) {
        if (closed) return
        val at = System.currentTimeMillis() - recordingStartMs
        synchronized(this) {
            if (closed) return
            if (written.get() + ReplayFormat.FRAME_HEADER + payload.size > maxBytes) {
                truncated = true
                log.warn(
                    "[yabranked] replay stream $index for ${player.name} hit its ${maxBytes / (1024 * 1024)} MB cap; " +
                        "recording stops here"
                )
                closeLocked()
                return
            }
            header.clear()
            header.put(protocol)
            header.putInt(at.coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            header.putInt(payload.size)
            try {
                out.write(header.array(), 0, ReplayFormat.FRAME_HEADER)
                out.write(payload)
            } catch (e: Exception) {
                log.warn("[yabranked] replay stream $index write failed; recording stops here", e)
                truncated = true
                closeLocked()
                return
            }
            written.addAndGet((ReplayFormat.FRAME_HEADER + payload.size).toLong())
            packets.incrementAndGet()
            if (firstMillis < 0) firstMillis = at
            lastMillis = at
        }
    }

    /**
     * Read [length] bytes from [offset], flushing first so the read sees
     * everything [sizeBytes] claims. Returns fewer bytes than asked for at the
     * end of the file, and an empty array when there is nothing new.
     */
    fun readFrom(offset: Long, length: Int): ByteArray {
        synchronized(this) { runCatching { out.flush() } }
        val available = (written.get() - offset).coerceAtMost(length.toLong())
        if (available <= 0) return ByteArray(0)
        val bytes = ByteArray(available.toInt())
        RandomAccessFile(file.toFile(), "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        return bytes
    }

    fun close() = synchronized(this) { closeLocked() }

    private fun closeLocked() {
        if (closed) return
        closed = true
        runCatching { out.flush() }
        runCatching { out.close() }
    }
}
