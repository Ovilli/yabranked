package dev.yabranked.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.Logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Wire format for result reports. Mirrors dev.yabranked.proto.MatchResultReport /
 * MatchOutcome — kept as a local copy because the proto module is a plain JVM
 * library that can't be nested into a Fabric mod jar without repackaging.
 * TODO(phase 3): publish proto as a nested-jar-capable artifact and share it.
 */
@Serializable
enum class WireOutcome {
    @SerialName("team_a")
    TEAM_A_WIN,

    @SerialName("team_b")
    TEAM_B_WIN,

    @SerialName("draw")
    DRAW,

    @SerialName("void")
    VOID,
}

@Serializable
data class WireResultReport(
    val matchId: String,
    val outcome: WireOutcome,
    val durationSeconds: Long,
    val teamAScore: Int,
    val teamBScore: Int,
    /** UUID of the player who forfeited (concede or no-show), null for a normal finish. */
    val forfeitedBy: String? = null,
    /**
     * Winning side index for matches with more than two sides. Null on a
     * two-side match, where [outcome] already says it.
     */
    val winningTeam: Int? = null,
    /** Side-ordered final scores, for matches with more than two sides. */
    val teamScores: List<Int> = emptyList(),
)

class BackendReporter(
    private val config: AgentConfig,
    private val log: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }
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
    fun reportResult(report: WireResultReport): Boolean {
        val body = json.encodeToString(WireResultReport.serializer(), report)
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
    fun reportReplayMeta(meta: WireMatchReplayMeta, complete: Boolean): Boolean {
        val body = json.encodeToString(WireMatchReplayMeta.serializer(), meta)
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
