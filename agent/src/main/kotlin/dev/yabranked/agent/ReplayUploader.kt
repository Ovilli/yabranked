package dev.yabranked.agent

import org.slf4j.Logger

/**
 * Ships growing packet streams to the backend while the match is still being
 * played.
 *
 * A recording is uploaded **incrementally**, in chunks, from the moment the match
 * starts — not once at the end. Two reasons, and the second is the one that
 * matters:
 *
 * - It is too big to send in one request. A four-player match is a hundred
 *   megabytes of packets, and the agent has a few seconds of the players' patience
 *   to spend before the result must land.
 * - A container that dies never gets to the end. That is not an edge case: it is
 *   how most matches that produce no replay produce no replay — an OOM kill, the
 *   liveness sweep voiding a match, or a player forfeiting over their own bearer
 *   token, which settles the match and tears the container down from outside. Each
 *   chunk that has landed is a chunk of match that survives all of it.
 *
 * The backend owns the authoritative length of each stream and the uploader
 * follows it: an append is offered at an offset, and a mismatch is answered with
 * the length the backend actually has, from which the uploader re-seeks. That
 * makes a retried chunk idempotent, which matters because "did that request
 * arrive" is a question a dying container cannot answer.
 */
class ReplayUploader(
    private val log: Logger,
    /**
     * Appends [bytes] to stream [index] at [offset]. Returns the stream's length
     * as the backend now has it, or null when the append did not land at all —
     * which is a reason to stop trying for now, not to skip forward.
     */
    private val append: (index: Int, offset: Long, bytes: ByteArray) -> Long?,
    /** Writes the recording's index. [complete] marks the recording finished. */
    private val putMeta: (meta: WireMatchReplayMeta, complete: Boolean) -> Boolean,
    /** How much is sent per request. */
    private val chunkBytes: Int = DEFAULT_CHUNK_BYTES,
) {
    /** Bytes of each stream the backend is known to hold, by stream index. */
    private val uploaded = HashMap<Int, Long>()

    /**
     * Send whatever has accumulated. Blocking, and called only from the
     * recorder's own single upload thread — two pumps at once would each be
     * appending at an offset the other had just moved.
     */
    fun pump(streams: List<ReplayStream>) {
        for (stream in streams) {
            var offset = uploaded.getOrDefault(stream.index, 0L)
            while (offset < stream.sizeBytes) {
                val bytes = stream.readFrom(offset, chunkBytes)
                if (bytes.isEmpty()) break
                val length = append(stream.index, offset, bytes)
                if (length == null) {
                    // Nothing landed. Leave the offset where it is and try again
                    // on the next checkpoint; the bytes are still on disk.
                    log.debug("[yabranked] replay chunk for stream ${stream.index} did not land")
                    return
                }
                if (length != offset + bytes.size) {
                    log.debug("[yabranked] replay stream ${stream.index} re-seeking to $length")
                }
                offset = length
                uploaded[stream.index] = offset
            }
        }
    }

    /**
     * Send the rest of the streams and then the index, marking the recording
     * complete. Returns whether the index landed — the streams are worth nothing
     * without it, since it is what says which bytes belong to whom.
     */
    fun finish(streams: List<ReplayStream>, meta: WireMatchReplayMeta): Boolean {
        pump(streams)
        return putMeta(meta, true)
    }

    /** Write the index mid-match, so a partial recording is already playable. */
    fun checkpoint(streams: List<ReplayStream>, meta: WireMatchReplayMeta) {
        pump(streams)
        putMeta(meta, false)
    }

    private companion object {
        /**
         * 512 KB. Small enough that a chunk lost to a dying container costs half a
         * second of match, large enough that a 50 MB stream is a hundred requests
         * rather than thousands.
         */
        const val DEFAULT_CHUNK_BYTES = 512 * 1024
    }
}
