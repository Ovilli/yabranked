package dev.yabranked.client

import kotlinx.serialization.json.Json
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

    var session: WireSessionResponse? = null
        private set

    sealed interface AuthResult {
        data class Ok(val session: WireSessionResponse) : AuthResult
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

        try {
            joinServer(serverId)
        } catch (e: Exception) {
            log.warn("Mojang joinServer failed", e)
            return AuthResult.Failed("Mojang session rejected (offline account?)")
        }

        val body = json.encodeToString(
            WireSessionRequest.serializer(),
            WireSessionRequest(username, serverId, clientVersion),
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
                    val parsed = json.decodeFromString(WireSessionResponse.serializer(), response.body())
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

    fun fetchProfile(uuid: String): WireProfile? = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/players/$uuid"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            json.decodeFromString(WireProfile.serializer(), response.body())
        } else null
    } catch (e: Exception) {
        log.warn("profile fetch failed", e)
        null
    }

    fun fetchLeaderboard(limit: Int = 25): List<WireProfile> = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/v1/leaderboard?limit=$limit"))
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) {
            json.decodeFromString(response.body())
        } else emptyList()
    } catch (e: Exception) {
        log.warn("leaderboard fetch failed", e)
        emptyList()
    }

    /** An open queue WebSocket; close it to leave the queue. */
    inner class QueueSocket internal constructor(
        private val socket: WebSocket,
    ) {
        fun leave() {
            runCatching {
                val message = json.encodeToString(
                    WireQueueClientMessage.serializer(),
                    WireQueueClientMessage.LeaveQueue,
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
        format: String,
        onMessage: (WireQueueServerMessage) -> Unit,
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
                        json.decodeFromString(WireQueueServerMessage.serializer(), text)
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
                WireQueueClientMessage.serializer(),
                WireQueueClientMessage.JoinQueue(format),
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
