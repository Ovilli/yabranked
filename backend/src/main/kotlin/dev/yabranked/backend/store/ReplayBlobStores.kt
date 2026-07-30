package dev.yabranked.backend.store

import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the packet bytes of a recording live.
 *
 * Deliberately not a database column. A recording is one file per participant of
 * tens of megabytes, appended to in half-megabyte chunks while the match is being
 * played and afterwards read back in ranges — which is a filesystem's job
 * description, and is close to the worst possible use of a `bytea`. Keeping it
 * out also means the replay format can grow without a migration, and that a
 * backup of the database stays the size of a database.
 *
 * ## Appends are offset-addressed, not sequential
 *
 * [append] takes the offset the caller believes the stream is at and returns the
 * length the store actually holds. A chunk offered at the wrong offset is
 * ignored, and the answer is the same either way: the current length. That is
 * what makes a retry safe — a container whose request timed out cannot know
 * whether it was applied, and with this it does not need to. It re-asks and is
 * told where it is.
 */
interface ReplayBlobStore {
    /** Bytes held for one stream; 0 when there is no such stream. */
    fun length(matchId: UUID, index: Int): Long

    /**
     * Append [bytes] to a stream if [offset] is exactly where it currently ends,
     * and return the stream's length afterwards. A stale or future offset writes
     * nothing and returns the unchanged length.
     */
    fun append(matchId: UUID, index: Int, offset: Long, bytes: ByteArray): Long

    /**
     * Read up to [length] bytes from [offset]. Returns fewer at the end of the
     * stream and an empty array past it — a client downloading a recording that is
     * still being uploaded reaches the end and comes back for more.
     */
    fun read(matchId: UUID, index: Int, offset: Long, length: Int): ByteArray

    /** Bytes held for the match across every stream. */
    fun totalBytes(matchId: UUID): Long

    /** Delete every stream of a match. Idempotent. */
    fun delete(matchId: UUID)
}

/**
 * Filesystem [ReplayBlobStore]: `<root>/<matchId>/<index>.yabr`.
 *
 * One lock per match rather than one global: two matches upload concurrently by
 * definition — that is what a backend running matches means — and the appends of
 * one must not wait behind the appends of another. The lock is per match rather
 * than per stream because deleting a match has to exclude all of its streams at
 * once.
 */
class FileReplayBlobStore(private val root: Path) : ReplayBlobStore {
    private val locks = ConcurrentHashMap<UUID, Any>()

    private fun <T> locked(matchId: UUID, block: () -> T): T =
        synchronized(locks.computeIfAbsent(matchId) { Any() }, block)

    private fun dirOf(matchId: UUID): Path = root.resolve(matchId.toString())

    private fun fileOf(matchId: UUID, index: Int): Path = dirOf(matchId).resolve("$index.yabr")

    override fun length(matchId: UUID, index: Int): Long = locked(matchId) {
        val file = fileOf(matchId, index)
        if (Files.exists(file)) Files.size(file) else 0
    }

    override fun append(matchId: UUID, index: Int, offset: Long, bytes: ByteArray): Long = locked(matchId) {
        val file = fileOf(matchId, index)
        Files.createDirectories(file.parent)
        RandomAccessFile(file.toFile(), "rw").use { raf ->
            val length = raf.length()
            // Not an error: the caller is allowed to be wrong about where it is,
            // and being told the truth is the whole protocol.
            if (offset != length) return@locked length
            raf.seek(length)
            raf.write(bytes)
            raf.length()
        }
    }

    override fun read(matchId: UUID, index: Int, offset: Long, length: Int): ByteArray = locked(matchId) {
        val file = fileOf(matchId, index)
        if (!Files.exists(file)) return@locked ByteArray(0)
        RandomAccessFile(file.toFile(), "r").use { raf ->
            if (offset >= raf.length()) return@locked ByteArray(0)
            raf.seek(offset)
            val available = (raf.length() - offset).coerceAtMost(length.toLong()).toInt()
            val bytes = ByteArray(available)
            raf.readFully(bytes)
            bytes
        }
    }

    override fun totalBytes(matchId: UUID): Long = locked(matchId) {
        val dir = dirOf(matchId)
        if (!Files.isDirectory(dir)) return@locked 0
        Files.list(dir).use { stream ->
            stream.mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }

    override fun delete(matchId: UUID) {
        locked(matchId) {
            val dir = dirOf(matchId)
            if (!Files.isDirectory(dir)) return@locked
            runCatching {
                Files.list(dir).use { stream ->
                    stream.forEach { runCatching { Files.delete(it) } }
                }
                Files.deleteIfExists(dir)
            }
        }
        // The lock outlives the data otherwise, and a backend that has run a
        // thousand matches would be holding a thousand dead monitors.
        locks.remove(matchId)
    }
}

/**
 * In-memory [ReplayBlobStore] for tests and for a backend with no replay volume.
 *
 * **Capped, because this is what an unconfigured deployment gets.** A recording is
 * 40–100 MB per player and is kept for the retention window, so on a small
 * instance a couple of matches is the whole heap — and the failure mode of an
 * uncapped store is not "replays stop working", it is the backend dying and
 * taking every live match with it. Past [maxTotalBytes] appends are refused: the
 * agent reads the unchanged length as a stale offset, which is exactly the
 * "you are not where you think you are" answer the protocol already handles.
 */
class InMemoryReplayBlobStore(
    /** Total across every match. 256 MB is generous for a 512 MB instance. */
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : ReplayBlobStore {
    private val log = org.slf4j.LoggerFactory.getLogger("replays")
    private val streams = ConcurrentHashMap<UUID, ConcurrentHashMap<Int, ByteArray>>()
    private val warned = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun length(matchId: UUID, index: Int): Long =
        (streams[matchId]?.get(index)?.size ?: 0).toLong()

    /** Everything held, across every match. */
    private fun heldBytes(): Long =
        streams.values.sumOf { match -> match.values.sumOf { it.size.toLong() } }

    override fun append(matchId: UUID, index: Int, offset: Long, bytes: ByteArray): Long {
        val match = streams.computeIfAbsent(matchId) { ConcurrentHashMap() }
        synchronized(match) {
            val current = match[index] ?: ByteArray(0)
            if (offset != current.size.toLong()) return current.size.toLong()
            if (heldBytes() + bytes.size > maxTotalBytes) {
                if (warned.compareAndSet(false, true)) {
                    log.warn(
                        "in-memory replay storage is full at {} MB — recordings are being truncated. " +
                            "Set YABRANKED_REPLAY_DIR or the S3 settings to keep them.",
                        maxTotalBytes / (1024 * 1024),
                    )
                }
                return current.size.toLong()
            }
            val merged = current + bytes
            match[index] = merged
            return merged.size.toLong()
        }
    }

    override fun read(matchId: UUID, index: Int, offset: Long, length: Int): ByteArray {
        val current = streams[matchId]?.get(index) ?: return ByteArray(0)
        if (offset >= current.size) return ByteArray(0)
        val end = (offset + length).coerceAtMost(current.size.toLong()).toInt()
        return current.copyOfRange(offset.toInt(), end)
    }

    override fun totalBytes(matchId: UUID): Long =
        streams[matchId]?.values?.sumOf { it.size.toLong() } ?: 0

    override fun delete(matchId: UUID) {
        streams.remove(matchId)
    }

    companion object {
        /**
         * 256 MB across every match. Sized against the smallest instance anyone
         * is likely to run this on rather than against how much a match wants:
         * losing the tail of a recording is a bad replay, and running out of heap
         * is a dead backend.
         */
        const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 * 1024
    }
}
