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
)

class BackendReporter(
    private val config: AgentConfig,
    private val log: Logger,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private fun post(path: String, body: String?): Boolean {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.backendUrl}$path"))
            .header("Authorization", "Bearer ${config.serverToken}")
            .header("Content-Type", "application/json")
            .let {
                if (body != null) it.POST(HttpRequest.BodyPublishers.ofString(body))
                else it.POST(HttpRequest.BodyPublishers.noBody())
            }
            .timeout(Duration.ofSeconds(15))
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                true
            } else {
                log.error("[yabranked] backend POST $path failed: ${response.statusCode()} ${response.body()}")
                false
            }
        } catch (e: Exception) {
            log.error("[yabranked] backend POST $path failed", e)
            false
        }
    }

    /** Tell the backend this match server is configured and accepting its players. */
    fun reportReady(): Boolean =
        post("/v1/internal/matches/ready", """{"matchId":"${config.matchId}"}""")

    /** Report the final result; retries a few times since this write matters. */
    fun reportResult(report: WireResultReport): Boolean {
        val body = json.encodeToString(WireResultReport.serializer(), report)
        repeat(3) { attempt ->
            if (post("/v1/internal/matches/result", body)) return true
            Thread.sleep(2000L * (attempt + 1))
        }
        return false
    }
}
