package dev.yabranked.client

import dev.yabranked.proto.*

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicReference

/**
 * HTTP/WebSocket client for the ranked backend. All methods are blocking and
 * must be called off the render thread; callbacks from the queue socket
 * arrive on the JDK http client's executor.
 */
class BackendClient(
    private val baseUrl: String,
    private val clientVersion: String,
) {
    private val log = LoggerFactory.getLogger("yabranked-client")
    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    var session: SessionResponse? = null
        private set

    sealed interface AuthResult {
        data class Ok(val session: SessionResponse) : AuthResult
        data class Outdated(val message: String) : AuthResult
        data class Failed(val message: String) : AuthResult
    }

    /**
     * Authenticate the given Minecraft account with the backend.
     * [joinServer] performs the Mojang session handshake for the serverId we
     * generate (the vanilla `joinServer` call); the backend then verifies
     * ownership via `hasJoined`.
     */
    fun authenticate(username: String, joinServer: (serverId: String) -> Unit): AuthResult {
        val serverId = BigInteger(160, SecureRandom()).toString(16)

        // If the Mojang handshake fails (offline/dev account) we still send the
        // request — the backend's hasJoined check is authoritative and will
        // reject it unless it runs in fake-auth mode.
        try {
            joinServer(serverId)
        } catch (e: Exception) {
            log.warn("Mojang joinServer failed (offline/dev account?) — proceeding, backend decides", e)
        }

        val body = json.encodeToString(
            SessionRequest.serializer(),
            SessionRequest(username, serverId, clientVersion),
        )
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/auth/session"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(15))
            .build()

        return try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when {
                response.statusCode() == 426 ->
                    AuthResult.Outdated("Ranked mod update required")
                response.statusCode() in 200..299 -> {
                    val parsed = json.decodeFromString(SessionResponse.serializer(), response.body())
                    session = parsed
                    AuthResult.Ok(parsed)
                }
                else -> {
                    log.warn("auth failed: ${response.statusCode()} ${response.body()}")
                    AuthResult.Failed("Login failed (${response.statusCode()})")
                }
            }
        } catch (e: Exception) {
            log.warn("auth request failed", e)
            AuthResult.Failed("Ranked backend unreachable")
        }
    }

    /**
     * Outcome of a backend read.
     *
     * Screens have to tell the failures apart — "the backend is down", "your
     * session is gone" and "your mod is too old" each need a different line and
     * a different next step from the player. Collapsing them all into null left
     * every screen able to say nothing better than "could not load".
     */
    sealed interface Fetch<out T> {
        data class Ok<out T>(val value: T) : Fetch<T>

        /** Any failure, carrying the line a screen should show for it. */
        sealed interface Error : Fetch<Nothing> {
            val message: String
        }

        /** The backend was never reached: refused, timed out, no DNS. */
        data object Offline : Error {
            override val message = "Ranked backend unreachable"
        }

        /** 401/403 — the bearer token expired or was revoked. */
        data object Unauthorized : Error {
            override val message = "Session expired — sign in again"
        }

        /** 426 — the backend's clientVersion gate rejected this build. */
        data object Outdated : Error {
            override val message = "Ranked mod update required"
        }

        /** Anything else the backend answered, including an undecodable body. */
        data class Failed(val status: Int) : Error {
            override val message = "Request failed ($status)"
        }
    }

    /**
     * One unauthenticated GET, decoded by [decode]. Every read below is this
     * same request with a different path, so the status → [Fetch] mapping is
     * written once instead of once per endpoint.
     */
    private fun <T> get(path: String, decode: (String) -> T): Fetch<T> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            log.warn("GET $path failed", e)
            return Fetch.Offline
        }
        val status = response.statusCode()
        return when {
            status == 401 || status == 403 -> Fetch.Unauthorized
            status == 426 -> Fetch.Outdated
            status in 200..299 -> try {
                Fetch.Ok(decode(response.body()))
            } catch (e: Exception) {
                // A body we cannot read is a backend fault, not a dead network.
                log.warn("GET $path: undecodable body", e)
                Fetch.Failed(status)
            }
            else -> {
                log.warn("GET $path: $status")
                Fetch.Failed(status)
            }
        }
    }

    fun fetchProfile(uuid: String): Fetch<PlayerProfile> =
        get("/v1/players/$uuid") { json.decodeFromString(PlayerProfile.serializer(), it) }

    fun fetchHistory(uuid: String, limit: Int = 10): Fetch<List<MatchHistoryEntry>> =
        get("/v1/players/$uuid/matches?limit=$limit") { json.decodeFromString(it) }

    /** Unlocked achievements for [uuid], oldest first; empty on any failure —
     *  the achievement strip is an embellishment, not a screen's content. */
    fun fetchAchievements(uuid: String): List<Achievement> =
        get<List<Achievement>>("/v1/players/$uuid/achievements") { json.decodeFromString(it) }
            .orElse(emptyList())

    /** Head-to-head record of [self] against [opponent]; null when unavailable. */
    fun fetchVersus(self: String, opponent: String): VersusRecord? =
        get("/v1/players/$self/versus/$opponent") { json.decodeFromString(VersusRecord.serializer(), it) }
            .orElse(null)

    /**
     * Update your own profile. [country] is a 2-letter code, "" to clear the
     * flag, or null to leave unchanged. Returns the refreshed profile, or null
     * on failure. Also refreshes the cached [session] profile on success.
     */
    fun updateProfile(
        country: String? = null,
        background: String? = null,
        hideFlag: Boolean? = null,
        hideRating: Boolean? = null,
    ): PlayerProfile? {
        val token = session?.token ?: return null
        return try {
            val body = json.encodeToString(
                ProfileUpdate.serializer(),
                ProfileUpdate(country, background, hideFlag, hideRating),
            )
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/players/me"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) {
                val profile = json.decodeFromString(PlayerProfile.serializer(), response.body())
                session = session?.copy(profile = profile)
                profile
            } else {
                log.warn("profile update failed: ${response.statusCode()} ${response.body()}")
                null
            }
        } catch (e: Exception) {
            log.warn("profile update request failed", e)
            null
        }
    }

    /** Report the opponent of [matchId]; returns a user-facing status line. */
    fun submitReport(matchId: String, reason: String): String {
        val token = session?.token ?: return "Not logged in"
        return try {
            val body = json.encodeToString(ReportRequest.serializer(), ReportRequest(matchId, reason))
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/reports"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(10))
                .build()
            when (http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()) {
                in 200..299 -> "Report submitted"
                409 -> "Already reported"
                else -> "Report failed"
            }
        } catch (e: Exception) {
            log.warn("report failed", e)
            "Report failed"
        }
    }

    fun fetchLeaderboard(limit: Int = 25, season: Int? = null): Fetch<List<PlayerProfile>> {
        val seasonParam = season?.let { "&season=$it" } ?: ""
        return get("/v1/leaderboard?limit=$limit$seasonParam") { json.decodeFromString(it) }
    }

    /** Current season number; falls back to 1 when the call fails. */
    fun fetchCurrentSeason(): Int =
        get("/v1/seasons/current") {
            json.parseToJsonElement(it).jsonObject["season"]?.jsonPrimitive?.int ?: 1
        }.orElse(1)

    /** An open queue WebSocket; close it to leave the queue. */
    inner class QueueSocket internal constructor(
        private val socket: WebSocket,
    ) {
        fun leave() {
            runCatching {
                val message = json.encodeToString(
                    QueueClientMessage.serializer(),
                    QueueClientMessage.LeaveQueue,
                )
                socket.sendText(message, true)
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "leave")
            }
        }
    }

    /**
     * Join the queue. [onMessage] fires for each server message; [onClosed]
     * when the socket ends for any reason. Returns null if not authenticated
     * or the connection fails.
     */
    fun joinQueue(
        format: MatchFormat,
        onMessage: (QueueServerMessage) -> Unit,
        onClosed: (reason: String?) -> Unit,
    ): QueueSocket? {
        val token = session?.token ?: return null
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/v1/queue?token=$token"

        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    runCatching {
                        json.decodeFromString(QueueServerMessage.serializer(), text)
                    }.onSuccess(onMessage)
                        .onFailure { log.warn("bad queue message: $text", it) }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                onClosed(reason.ifEmpty { null })
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                log.warn("queue socket error", error)
                onClosed(error.message)
            }
        }

        return try {
            val socket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), listener)
                .join()
            val joinMessage = json.encodeToString(
                QueueClientMessage.serializer(),
                QueueClientMessage.JoinQueue(format),
            )
            socket.sendText(joinMessage, true).join()
            QueueSocket(socket)
        } catch (e: Exception) {
            log.warn("queue connect failed", e)
            onClosed(e.message ?: "connection failed")
            null
        }
    }

    companion object {
        val stateRef = AtomicReference<BackendClient?>(null)
    }
}

/**
 * The value on success, [fallback] on any failure. For reads whose failure no
 * screen surfaces — there is no error card for a missing achievement strip, so
 * making those callers match on [BackendClient.Fetch] would be noise.
 */
fun <T> BackendClient.Fetch<T>.orElse(fallback: T): T =
    if (this is BackendClient.Fetch.Ok) value else fallback
