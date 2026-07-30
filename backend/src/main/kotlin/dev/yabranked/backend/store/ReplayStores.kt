package dev.yabranked.backend.store

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A stored recording's *index*, plus the three independent reasons it is still
 * on disk.
 *
 * The packets themselves are not here. A recording is a packet capture — tens of
 * megabytes per player — and lives in a [ReplayBlobStore]; this row is the small
 * part: what the recording is of, how big it got, and who wants it kept.
 *
 * [savedBy] is the players who asked to keep it, [underReview] is the moderator
 * hold a report puts on it, and [expiresAt] is the default drop date for a
 * replay nobody claimed. They are kept apart on purpose: a player un-saving
 * their copy must not delete evidence in an open report, and a report being
 * closed must not delete a replay a player saved.
 */
data class ReplayRecord(
    val matchId: UUID,
    /**
     * The index, serialized — `MatchReplayMeta` as the agent sent it. Stored as
     * text so the store is dumb about it, and empty while the first chunks are
     * arriving but the container has not sent an index yet.
     */
    val meta: String,
    val recordedAt: Instant,
    val durationSeconds: Long,
    /** Players who pinned it against their quota. */
    val savedBy: Set<UUID> = emptySet(),
    /** Held for moderator review because the match was reported. */
    val underReview: Boolean = false,
    /** When an unpinned, unreviewed replay is dropped. */
    val expiresAt: Instant,
    /** Packet bytes held for this match across every stream. */
    val sizeBytes: Long = 0,
    /**
     * False while the container is still uploading. A partial recording is
     * playable up to where it got to, which is the whole reason checkpoints
     * exist — but a viewer that does not say so is claiming a match ended where
     * the upload stopped.
     */
    val complete: Boolean = false,
) {
    /** Whether anything is keeping this past [expiresAt]. */
    val pinned: Boolean get() = savedBy.isNotEmpty() || underReview

    /** A recording with no index cannot be played, whatever bytes it has. */
    val playable: Boolean get() = meta.isNotEmpty()
}

interface ReplayStore {
    /**
     * Create the row for a match if it has none, so bytes can be appended to it.
     *
     * The first chunk of a recording arrives *before* its index: the agent uploads
     * stream tails as the match is played and only describes them afterwards. The
     * row therefore has to exist before anything is known about the recording
     * except which match it belongs to.
     */
    fun ensure(matchId: UUID, recordedAt: Instant, expiresAt: Instant, underReview: Boolean)

    /** Write (or replace) the recording's index. */
    fun putMeta(matchId: UUID, meta: String, durationSeconds: Long, complete: Boolean)

    /** Record the byte total the blob store now holds for this match. */
    fun setSizeBytes(matchId: UUID, bytes: Long)

    fun get(matchId: UUID): ReplayRecord?

    /**
     * Metadata-only read for many matches at once. The list screens ask for these
     * and never want the index text, which is the largest column left.
     */
    fun summaries(matchIds: Collection<UUID>): Map<UUID, ReplayRecord>

    /** Replays [player] has pinned, newest first. */
    fun savedFor(player: UUID): List<ReplayRecord>

    /**
     * Pin [matchId] for [player]. False when it is already pinned by them or
     * there is no such replay; the quota check is the caller's, since only it
     * knows the limit.
     */
    fun save(matchId: UUID, player: UUID): Boolean

    fun unsave(matchId: UUID, player: UUID): Boolean

    /** Put (or lift) the moderator hold. */
    fun setUnderReview(matchId: UUID, underReview: Boolean)

    /** Every replay currently held for review, newest first. */
    fun underReview(limit: Int): List<ReplayRecord>

    /**
     * Drop every row past its expiry that nothing is pinning, and return the
     * matches dropped.
     *
     * The ids are the point: the packets live outside this store, and a pruner
     * that only deleted rows would leave the bytes behind forever — which is the
     * one failure mode a retention sweep exists to prevent.
     */
    fun pruneExpired(now: Instant): List<UUID>
}

class InMemoryReplayStore : ReplayStore {
    private val replays = ConcurrentHashMap<UUID, ReplayRecord>()

    override fun ensure(matchId: UUID, recordedAt: Instant, expiresAt: Instant, underReview: Boolean) {
        replays.computeIfAbsent(matchId) {
            ReplayRecord(
                matchId = matchId,
                meta = "",
                recordedAt = recordedAt,
                durationSeconds = 0,
                underReview = underReview,
                expiresAt = expiresAt,
            )
        }
    }

    override fun putMeta(matchId: UUID, meta: String, durationSeconds: Long, complete: Boolean) {
        replays.computeIfPresent(matchId) { _, existing ->
            existing.copy(meta = meta, durationSeconds = durationSeconds, complete = complete)
        }
    }

    override fun setSizeBytes(matchId: UUID, bytes: Long) {
        replays.computeIfPresent(matchId) { _, existing -> existing.copy(sizeBytes = bytes) }
    }

    override fun get(matchId: UUID): ReplayRecord? = replays[matchId]

    override fun summaries(matchIds: Collection<UUID>): Map<UUID, ReplayRecord> =
        matchIds.distinct().mapNotNull(replays::get).associateBy { it.matchId }

    override fun savedFor(player: UUID): List<ReplayRecord> =
        replays.values.filter { player in it.savedBy }.sortedByDescending { it.recordedAt }

    override fun save(matchId: UUID, player: UUID): Boolean {
        val current = replays[matchId] ?: return false
        if (player in current.savedBy) return false
        replays[matchId] = current.copy(savedBy = current.savedBy + player)
        return true
    }

    override fun unsave(matchId: UUID, player: UUID): Boolean {
        val current = replays[matchId] ?: return false
        if (player !in current.savedBy) return false
        replays[matchId] = current.copy(savedBy = current.savedBy - player)
        return true
    }

    override fun setUnderReview(matchId: UUID, underReview: Boolean) {
        val current = replays[matchId] ?: return
        replays[matchId] = current.copy(underReview = underReview)
    }

    override fun underReview(limit: Int): List<ReplayRecord> =
        replays.values.filter { it.underReview }.sortedByDescending { it.recordedAt }.take(limit)

    override fun pruneExpired(now: Instant): List<UUID> {
        val doomed = replays.values.filter { !it.pinned && !it.expiresAt.isAfter(now) }
        doomed.forEach { replays.remove(it.matchId, it) }
        return doomed.map { it.matchId }
    }
}

/**
 * Replay retention policy, in one place so the API, the pruner and the client's
 * quota display cannot disagree about it.
 *
 * The numbers are byte-shaped now rather than file-shaped. A recording used to be
 * a few hundred kilobytes of position samples; it is now a packet capture, which
 * is three orders of magnitude larger, and a cap that counts only files stopped
 * describing the thing being rationed.
 */
data class ReplayPolicy(
    /** Replays one player may pin at a time. */
    val savedPerPlayer: Int = 10,
    /**
     * Bytes one player's pinned replays may occupy, 0 for no byte limit. Ten
     * saved matches at ~50 MB a stream is the shape this is sized for.
     */
    val savedBytesPerPlayer: Long = 2L * 1024 * 1024 * 1024,
    /** How long an unpinned replay is kept, giving the player time to decide. */
    val retentionDays: Long = 7,
    /**
     * Largest single chunk an agent may append. Bounds the memory one request can
     * make the backend hold, which the old whole-recording upload did not.
     */
    val maxChunkBytes: Int = 2 * 1024 * 1024,
    /** Largest recording the backend will hold for one match, across all streams. */
    val maxRecordingBytes: Long = 512L * 1024 * 1024,
    /** Largest index document. It is a card and a timeline; a megabyte is generous. */
    val maxMetaBytes: Int = 1024 * 1024,
)
