package dev.yabranked.client.replay

import dev.yabranked.client.BackendClient
import dev.yabranked.proto.MatchReplayMeta
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Downloads recordings and keeps them on the player's disk.
 *
 * A recording has to be local before it can be played: playback feeds packets
 * into the client at the wall-clock rate they were captured at, seeks backwards by
 * replaying from the start, and cannot pause for the network in the middle of
 * either. So the viewer downloads first and watches second, and says so.
 *
 * The cache is resumable, which is not a nicety at this size. It stores each
 * stream at exactly the length it has been verified to hold and asks for the rest
 * from there, so a player who closes the game halfway through a fifty-megabyte
 * download does not start again.
 */
class ReplayCache(private val root: Path) {
    private val log = LoggerFactory.getLogger("yabranked-replay")
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    /** How a download is getting on, for the screen that is waiting on it. */
    data class Progress(val bytes: Long, val total: Long, val stream: Int, val streams: Int) {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    sealed interface Result {
        /** Everything is on disk and indexed. */
        data class Ready(val meta: MatchReplayMeta, val dir: Path) : Result
        data class Failed(val message: String) : Result
    }

    fun dirOf(matchId: String): Path = root.resolve(matchId)

    fun streamPath(matchId: String, index: Int): Path = dirOf(matchId).resolve("$index.yabr")

    private fun metaPath(matchId: String): Path = dirOf(matchId).resolve("meta.json")

    /** The cached index for a match, if it has been downloaded before. */
    fun cachedMeta(matchId: String): MatchReplayMeta? = runCatching {
        json.decodeFromString(MatchReplayMeta.serializer(), Files.readString(metaPath(matchId)))
    }.getOrNull()

    /**
     * Fetch whatever of [matchId]'s recording is not already here. Blocking —
     * call it from a worker, never the render thread.
     *
     * The index is re-fetched every time even when it is cached, because a
     * recording that was partial last time may be complete now, and the streams
     * are then longer than the cache believes.
     */
    fun download(
        client: BackendClient,
        matchId: String,
        onProgress: (Progress) -> Unit = {},
    ): Result {
        val fetched = client.fetchReplayMeta(matchId)
        val meta = when (fetched) {
            is BackendClient.Fetch.Ok -> fetched.value
            is BackendClient.Fetch.Error -> {
                // A cached copy is a real answer when the backend is unreachable:
                // the recording is already here, and refusing to play it because
                // the network is down would be refusing for no reason.
                val cached = cachedMeta(matchId)
                if (cached != null) cached else return Result.Failed(fetched.message)
            }
        }
        if (!meta.playableOn(net.minecraft.SharedConstants.getProtocolVersion())) {
            return Result.Failed(
                "That replay was recorded on Minecraft ${meta.gameVersion.ifEmpty { "another version" }} " +
                    "and cannot be played here"
            )
        }
        if (meta.streams.isEmpty()) return Result.Failed("That replay has no recorded streams")

        val dir = dirOf(matchId)
        runCatching { Files.createDirectories(dir) }
            .onFailure { return Result.Failed("Could not write to the replay cache") }

        val total = meta.totalBytes
        var done = 0L
        for (info in meta.streams) {
            val path = streamPath(matchId, info.index)
            var have = if (Files.exists(path)) Files.size(path) else 0L
            // A cached stream longer than the index says is a stale file from a
            // recording that has since been replaced; start it again rather than
            // splicing two different matches together.
            if (have > info.sizeBytes) {
                runCatching { Files.delete(path) }
                have = 0
            }
            done += have
            onProgress(Progress(done, total, info.index, meta.streams.size))
            while (have < info.sizeBytes) {
                val chunk = client.fetchReplayChunk(matchId, info.index, have, CHUNK_BYTES)
                    ?: return Result.Failed("The replay download failed — try again")
                if (chunk.bytes.isEmpty()) {
                    // The backend has less than the index promised: the container
                    // was still uploading when the index was written. What is here
                    // is playable; stopping is better than looping on an empty
                    // answer forever.
                    log.info("replay stream {} is short of its indexed size; playing what arrived", info.index)
                    break
                }
                runCatching {
                    Files.write(
                        path, chunk.bytes,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND,
                    )
                }.onFailure { return Result.Failed("Could not write the replay to disk") }
                have += chunk.bytes.size
                done += chunk.bytes.size
                onProgress(Progress(done, total, info.index, meta.streams.size))
            }
        }

        runCatching {
            Files.writeString(metaPath(matchId), json.encodeToString(MatchReplayMeta.serializer(), meta))
        }.onFailure { log.warn("could not cache the replay index", it) }

        prune(keep = matchId)

        return Result.Ready(meta, dir)
    }

    /**
     * Drop the oldest recordings until the cache is under [maxBytes].
     *
     * A recording is tens of megabytes and nothing ever removed one. The library
     * screen shows the total and offers a Delete, which is real management but
     * only for a player who thinks to look — everyone else watches ten matches
     * and silently gives up half a gigabyte of their game directory. The backend
     * caps what it stores per player; this is the same bargain on the machine
     * that has to hold it.
     *
     * Oldest by recording time rather than by last watched: what makes a
     * recording worth keeping is that it is recent, and a rewatch does not make
     * an old match new. [keep] is never dropped whatever its age — evicting the
     * recording that was just downloaded, in the call that downloaded it, would
     * be an expensive way to achieve nothing.
     */
    fun prune(keep: String? = null, maxBytes: Long = MAX_CACHE_BYTES) {
        val entries = cached()
        var total = entries.sumOf { it.bytes }
        if (total <= maxBytes) return
        // Oldest first: cached() is newest first, so this walks it backwards.
        for (entry in entries.asReversed()) {
            if (total <= maxBytes) return
            val id = entry.meta.matchId
            if (id == keep) continue
            log.info("replay cache over {} bytes; dropping {}", maxBytes, id)
            evict(id)
            total -= entry.bytes
        }
    }

    /** One recording sitting on this machine, and how much room it takes. */
    data class Cached(val meta: MatchReplayMeta, val dir: Path, val bytes: Long)

    /**
     * Every recording on disk, newest first.
     *
     * Read from the cached index rather than asked of the backend, and that is the
     * point rather than an optimisation. A recording *is* a local file — tens of
     * megabytes of it — so the list of what can be watched right now is a question
     * the disk answers and the network cannot. It survives a backend restart, an
     * expired retention window and being offline, all of which otherwise leave a
     * player holding a hundred megabytes of match they have no way to open.
     */
    fun cached(): List<Cached> = runCatching {
        if (!Files.isDirectory(root)) return emptyList()
        Files.list(root).use { dirs ->
            dirs.filter(Files::isDirectory).toList()
        }.mapNotNull { dir ->
            val meta = cachedMeta(dir.fileName.toString()) ?: return@mapNotNull null
            val bytes = meta.streams.sumOf { info ->
                val path = dir.resolve("${info.index}.yabr")
                if (Files.exists(path)) runCatching { Files.size(path) }.getOrDefault(0L) else 0L
            }
            Cached(meta, dir, bytes)
        }.sortedByDescending { it.meta.recordedFrom }
    }.getOrElse {
        log.warn("could not read the replay cache", it)
        emptyList()
    }

    /**
     * Delete one cached recording. The backend still has it if it is saved.
     *
     * Logged, because this throws away tens of megabytes that may be the only
     * copy — the backend's own row is in memory unless it runs on Postgres, so a
     * restart there plus an evict here loses the match for good. When a cache
     * empties unexpectedly, this line is what says who did it.
     */
    fun evict(matchId: String) {
        log.info("evicting cached replay {}", matchId)
        runCatching {
            val dir = dirOf(matchId)
            if (!Files.isDirectory(dir)) return
            Files.list(dir).use { stream -> stream.forEach { runCatching { Files.delete(it) } } }
            Files.deleteIfExists(dir)
        }
    }

    /** Bytes the cache is using, for the options screen that offers to clear it. */
    fun sizeBytes(): Long = runCatching {
        if (!Files.isDirectory(root)) return 0
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile).mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }.sum()
        }
    }.getOrDefault(0)

    fun clear() {
        log.info("clearing the whole replay cache")
        runCatching {
            if (!Files.isDirectory(root)) return
            Files.list(root).use { dirs -> dirs.forEach { evict(it.fileName.toString()) } }
        }
    }

    companion object {
        /**
         * 4 MB a request, matching the backend's page size. Larger would mean a
         * coarser progress bar and a longer redo after a dropped connection.
         */
        private const val CHUNK_BYTES = 4 * 1024 * 1024

        /**
         * How much of the game directory the cache may take before the oldest
         * recordings are dropped.
         *
         * A packet capture runs to tens of megabytes a player, so this is a
         * handful of matches rather than a library. It is deliberately generous
         * enough that a session of watching back today's games never evicts one
         * of them mid-session, and small enough that a player who forgets the
         * feature exists does not find a gigabyte missing a month later.
         */
        const val MAX_CACHE_BYTES = 750L * 1024 * 1024

        /** Under the game directory, so it moves with the instance. */
        fun default(): ReplayCache =
            ReplayCache(Minecraft.getInstance().gameDirectory.toPath().resolve("yabranked/replays"))
    }
}
