package dev.yabranked.client.replay

import dev.yabranked.proto.MatchReplayMeta
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.ReplayStreamInfo
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cache's own housekeeping.
 *
 * A packet capture is tens of megabytes and nothing used to remove one, so a
 * player who watched a few matches back gave up a chunk of their game directory
 * and only found out if they opened the library screen.
 */
class ReplayCachePruneTest {

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private lateinit var root: Path

    private fun cache() = ReplayCache(root)

    /** Writes a cached recording of [bytes] recorded at [recordedFrom]. */
    private fun put(matchId: String, bytes: Int, recordedFrom: Long) {
        val dir = root.resolve(matchId)
        Files.createDirectories(dir)
        Files.write(dir.resolve("0.yabr"), ByteArray(bytes))
        val meta = MatchReplayMeta(
            matchId = matchId,
            recordedFrom = recordedFrom,
            streams = listOf(
                ReplayStreamInfo(
                    index = 0,
                    player = PlayerRef(uuid = matchId, name = matchId),
                    sizeBytes = bytes.toLong(),
                ),
            ),
        )
        Files.writeString(dir.resolve("meta.json"), json.encodeToString(MatchReplayMeta.serializer(), meta))
    }

    private fun ids() = cache().cached().map { it.meta.matchId }.toSet()

    private fun setUpRoot() {
        root = Files.createTempDirectory("yabranked-replay-cache")
    }

    @Test
    fun `a cache under the limit keeps everything`() {
        setUpRoot()
        put("a", 100, recordedFrom = 1)
        put("b", 100, recordedFrom = 2)

        cache().prune(maxBytes = 1000)

        assertEquals(setOf("a", "b"), ids())
    }

    @Test
    fun `the oldest recordings go first`() {
        setUpRoot()
        put("oldest", 100, recordedFrom = 1)
        put("middle", 100, recordedFrom = 2)
        put("newest", 100, recordedFrom = 3)

        cache().prune(maxBytes = 250)

        // 300 bytes over a 250 limit: dropping the oldest is enough.
        assertEquals(setOf("middle", "newest"), ids())
    }

    @Test
    fun `the recording just downloaded is never the one dropped`() {
        setUpRoot()
        // The kept one is also the oldest, so age alone would evict it.
        put("wanted", 100, recordedFrom = 1)
        put("newer", 100, recordedFrom = 2)

        cache().prune(keep = "wanted", maxBytes = 150)

        assertTrue("wanted" in ids(), "evicted the recording the caller had just fetched")
    }

    @Test
    fun `pruning stops as soon as the cache fits`() {
        setUpRoot()
        put("a", 100, recordedFrom = 1)
        put("b", 100, recordedFrom = 2)
        put("c", 100, recordedFrom = 3)

        cache().prune(maxBytes = 200)

        assertEquals(2, ids().size, "dropped more than it had to: ${ids()}")
    }
}
