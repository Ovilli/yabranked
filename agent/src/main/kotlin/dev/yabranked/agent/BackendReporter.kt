package dev.yabranked.agent

import dev.yabranked.proto.MatchReplayMeta
import dev.yabranked.proto.MatchResultReport
import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/*
 * The result report and the replay index are `:proto`'s own types now, not
 * copies of them.
 *
 * They used to be `WireResultReport`/`WireOutcome` here, under a comment saying
 * proto "can't be nested into a Fabric mod jar without repackaging" — which
 * stopped being true when `:client` solved it by flattening proto's classes
 * instead of nesting them, and left one wire contract with two declarations that
 * had to be edited together. The backend has always decoded these with proto's
 * serializers, so the copies were only ever a second opinion about a shape
 * somebody else owned.
 */
class BackendReporter(
    private val config: AgentConfig,
    private val log: Logger,
) {
    private val json = AgentJson
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Status and body of a POST; status -1 when it never got an answer at all. */
    private fun postFor(
        path: String,
        body: HttpRequest.BodyPublisher,
        contentType: String,
        timeout: Duration,
        quiet: Boolean = false,
    ): Pair<Int, String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.backendUrl}$path"))
            .header("Authorization", "Bearer ${config.serverToken}")
            .header("Content-Type", contentType)
            .POST(body)
            .timeout(timeout)
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299 && !quiet) {
                log.error("[yabranked] backend POST $path failed: ${response.statusCode()} ${response.body()}")
            }
            response.statusCode() to response.body().orEmpty()
        } catch (e: Exception) {
            if (!quiet) log.error("[yabranked] backend POST $path failed", e)
            -1 to ""
        }
    }

    /** Status code of the POST, or -1 when it never got an answer at all. */
    private fun postStatus(path: String, body: String?, timeout: Duration = Duration.ofSeconds(15)): Int =
        postFor(
            path,
            if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody(),
            "application/json",
            timeout,
        ).first

    private fun post(path: String, body: String?, timeout: Duration = Duration.ofSeconds(15)): Boolean =
        postStatus(path, body, timeout) in 200..299

    /** Tell the backend this match server is configured and accepting its players. */
    fun reportReady(): Boolean =
        post("/v1/internal/matches/ready", """{"matchId":"${config.matchId}"}""")

    /** Report the final result; retries a few times since this write matters. */
    fun reportResult(report: MatchResultReport): Boolean {
        val body = json.encodeToString(MatchResultReport.serializer(), report)
        repeat(3) { attempt ->
            val status = postStatus("/v1/internal/matches/result", body)
            if (status in 200..299) return true
            // 409 "already settled" is the ordinary end of a match a player
            // forfeited over their own token, and 4xx in general is a verdict,
            // not a hiccup. Retrying one only spends the container's remaining
            // life re-asking a question that has been answered.
            if (status in 400..499) {
                log.warn("[yabranked] backend refused the result ($status); not retrying")
                return false
            }
            Thread.sleep(2000L * (attempt + 1))
        }
        return false
    }

    /**
     * Append captured packet bytes to one stream of this match's recording.
     *
     * Returns the stream's length as the backend now holds it, or null when the
     * append did not land. The backend answering with a length rather than an
     * acknowledgement is what makes a retry safe: the agent re-seeks to whatever
     * the backend has instead of guessing whether a request that timed out was
     * applied, and a duplicate chunk is refused with the same answer as a
     * successful one.
     */
    fun appendReplayStream(index: Int, offset: Long, bytes: ByteArray): Long? {
        val (status, body) = postFor(
            "/v1/internal/matches/${config.matchId}/replay/streams/$index?offset=$offset",
            HttpRequest.BodyPublishers.ofByteArray(bytes),
            "application/octet-stream",
            CHUNK_TIMEOUT,
            // Chunks fail routinely on a slow link and retry on the next
            // checkpoint. Logging each at error level would bury the one line
            // that matters, which is the result report.
            quiet = true,
        )
        if (status !in 200..299 && status != 409) {
            log.debug("[yabranked] replay chunk for stream $index rejected ($status)")
            return null
        }
        // Both the accepted and the "you are at the wrong offset" answers carry
        // the authoritative length; 409 is the resync, not a failure.
        return LENGTH.find(body)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Upload the recording's index. Sent *before* the result — see the note in
     * `YabRankedAgent.reportAndShutdown` for why the order matters.
     *
     * One attempt, on a short leash. Going first means every second this takes is
     * a second the players sit on the result-loading screen, so it is capped well
     * inside that budget: a replay is a nice thing to have and the result is the
     * thing that must land. It is small — the packets went up during the match.
     */
    fun reportReplayMeta(meta: MatchReplayMeta, complete: Boolean): Boolean {
        val body = json.encodeToString(MatchReplayMeta.serializer(), meta)
        return post(
            "/v1/internal/matches/${config.matchId}/replay?complete=$complete",
            body,
            timeout = REPLAY_TIMEOUT,
        )
    }

    private companion object {
        /** Long enough for a small JSON index on a slow link, short enough not to
         *  push the result past the client's poll window. */
        val REPLAY_TIMEOUT: Duration = Duration.ofSeconds(8)

        /** Chunks are half a megabyte and go up while the match is being played. */
        val CHUNK_TIMEOUT: Duration = Duration.ofSeconds(20)

        /** `{"length":123}`, without pulling a parser in for one number. */
        val LENGTH = Regex("\"length\"\\s*:\\s*(\\d+)")
    }
}
