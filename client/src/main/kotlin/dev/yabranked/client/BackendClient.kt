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
import java.util.concurrent.CompletableFuture
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
            // Signing these in is optional — they all answer to anyone — but it
            // is what makes a friend's friends-only profile fields visible.
            .also { builder -> session?.token?.let { builder.header("Authorization", "Bearer $it") } }
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

    /**
     * A player by name rather than by uuid, for a search box.
     *
     * The name is percent-encoded: Minecraft names cannot contain a slash or a
     * space, but the box will happily hold whatever the player typed, and a raw
     * value there would build a URL that means something else.
     */
    fun fetchProfileByName(name: String): Fetch<PlayerProfile> =
        get("/v1/players/by-name/${java.net.URLEncoder.encode(name, Charsets.UTF_8)}") {
            json.decodeFromString(PlayerProfile.serializer(), it)
        }

    fun fetchHistory(uuid: String, limit: Int = 10, offset: Int = 0): Fetch<List<MatchHistoryEntry>> =
        get("/v1/players/$uuid/matches?limit=$limit&offset=$offset") { json.decodeFromString(it) }

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
        privacy: PrivacySettings? = null,
    ): PlayerProfile? {
        val token = session?.token ?: return null
        return try {
            val body = json.encodeToString(
                ProfileUpdate.serializer(),
                ProfileUpdate(country, background, hideFlag, hideRating, privacy),
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

    /**
     * File a report; returns a user-facing status line.
     *
     * Either [matchId] (post-match, where the game is unambiguous) or [accused]
     * (from a profile, where it is not) — the backend resolves the other from
     * the match roster and refuses anything it cannot attribute.
     */
    fun submitReport(matchId: String?, reason: String, accused: String? = null): String {
        val token = session?.token ?: return "Not logged in"
        return try {
            val body = json.encodeToString(
                ReportRequest.serializer(),
                ReportRequest(matchId = matchId, reason = reason, accused = accused),
            )
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
                // The profile path only has a player, so "no shared match" is a
                // real and common answer, not a bug worth hiding behind "failed".
                404 -> "No match with that player to report"
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

    // --- Social ---

    /**
     * One authenticated request with an optional JSON body. The social
     * endpoints are all "press a button, get a yes or a reason", so they share
     * one helper rather than repeating the token/timeout/status dance.
     *
     * Returns null on success and a user-facing line on failure, which is the
     * shape every caller here actually wants.
     */
    private fun act(method: String, path: String, body: String? = null): String? {
        val token = session?.token ?: return "Not signed in"
        return try {
            val publisher = body?.let(HttpRequest.BodyPublishers::ofString)
                ?: HttpRequest.BodyPublishers.noBody()
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl$path"))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .timeout(Duration.ofSeconds(10))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() in 200..299) null
            // The backend's refusals are written for players ("you can only add
            // players you have played with"), so they are shown as-is rather
            // than replaced with a status code.
            else errorMessageOf(response.body()) ?: "Request failed (${response.statusCode()})"
        } catch (e: Exception) {
            log.warn("$method $path failed", e)
            "Ranked backend unreachable"
        }
    }

    private fun errorMessageOf(body: String): String? = runCatching {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    /** One authenticated GET; the reads that are only visible to their owner. */
    private fun <T> authedGet(path: String, decode: (String) -> T): Fetch<T> {
        val token = session?.token ?: return Fetch.Unauthorized
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl$path"))
            .header("Authorization", "Bearer $token")
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
            status in 200..299 -> runCatching { Fetch.Ok(decode(response.body())) }
                .getOrElse { Fetch.Failed(status) }
            else -> Fetch.Failed(status)
        }
    }

    fun fetchFriends(): Fetch<FriendListResponse> =
        authedGet("/v1/friends") { json.decodeFromString(FriendListResponse.serializer(), it) }

    /** Everyone the player has shared a match with — the friend-request pool. */
    fun fetchRecentPlayers(): Fetch<List<RecentPlayer>> =
        authedGet("/v1/friends/recent") { json.decodeFromString(it) }

    fun sendFriendRequest(uuid: String): String? =
        act(
            "POST", "/v1/friends/requests",
            json.encodeToString(FriendRequestCreate.serializer(), FriendRequestCreate(uuid = uuid)),
        )

    /** Request by name; the backend resolves it against every known account. */
    fun sendFriendRequestByName(name: String): String? =
        act(
            "POST", "/v1/friends/requests",
            json.encodeToString(FriendRequestCreate.serializer(), FriendRequestCreate(name = name)),
        )

    /**
     * The match the backend believes this player is in, or null for none.
     *
     * `Fetch.Offline` and `Fetch.Failed` are *not* "no match": the caller uses
     * this to decide whether to tear down its own match state, and a flaky
     * request must never be read as "your match is over".
     */
    fun fetchLiveMatch(): Fetch<QueueServerMessage.MatchFound?> =
        authedGet("/v1/players/me/match") { body ->
            // 204 for no match, so an empty body is the answer rather than a
            // decode failure.
            if (body.isBlank()) null
            else json.decodeFromString(QueueServerMessage.MatchFound.serializer(), body)
        }

    /**
     * Concede [matchId], whether or not this client is connected to its server.
     *
     * The match server's `/forfeit` command is the nicer path when it is
     * reachable — it announces the concession in chat and ends the game cleanly
     * — but it is unreachable from the menus, which is where a player who has
     * already left the match actually is. This settles it either way.
     */
    fun forfeitMatch(matchId: String): String? = act("POST", "/v1/matches/$matchId/forfeit")

    fun acceptFriendRequest(id: String): String? = act("POST", "/v1/friends/requests/$id/accept")

    fun dismissFriendRequest(id: String): String? = act("DELETE", "/v1/friends/requests/$id")

    fun removeFriend(uuid: String): String? = act("DELETE", "/v1/friends/$uuid")

    /** Teammates still endorsable for [matchId]; null once the window closed. */
    fun fetchEndorsementPrompt(matchId: String): EndorsementPrompt? =
        authedGet("/v1/matches/$matchId/endorsements") {
            json.decodeFromString(EndorsementPrompt.serializer(), it)
        }.orElse(null)

    fun endorse(matchId: String, teammates: List<String>, category: EndorsementCategory): String? =
        act(
            "POST", "/v1/endorsements",
            json.encodeToString(
                EndorsementRequest.serializer(),
                EndorsementRequest(matchId, teammates, category),
            ),
        )

    // --- Replays ---

    /**
     * The *index* of a match's recording: the card, the timeline, and which
     * packet streams it has. Small; the streams themselves are fetched by
     * [fetchReplayChunk] into a local cache.
     */
    fun fetchReplayMeta(matchId: String): Fetch<MatchReplayMeta> =
        authedGet("/v1/matches/$matchId/replay") {
            json.decodeFromString(MatchReplayMeta.serializer(), it)
        }

    /** One range of one packet stream, plus the stream's total length. */
    data class ReplayChunk(val bytes: ByteArray, val streamLength: Long)

    /**
     * Download a range of a recording's packet stream.
     *
     * Ranged rather than whole-stream because a recording is tens of megabytes
     * per player: the viewer shows progress against it, and a download
     * interrupted halfway has to resume rather than start again. The response's
     * `X-Replay-Stream-Length` is how the caller knows when it has all of it —
     * and it can grow between calls while the match is still being recorded.
     */
    fun fetchReplayChunk(matchId: String, stream: Int, offset: Long, length: Int): ReplayChunk? {
        val token = session?.token ?: return null
        return try {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$baseUrl/v1/matches/$matchId/replay/streams/$stream?offset=$offset&length=$length"))
                .header("Authorization", "Bearer $token")
                .GET()
                // Generous: this is megabytes over whatever link the player has,
                // and a viewer that gave up at ten seconds would fail on exactly
                // the connections that most need the resume to work.
                .timeout(Duration.ofSeconds(60))
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
            if (response.statusCode() !in 200..299) return null
            ReplayChunk(
                bytes = response.body(),
                streamLength = response.headers().firstValue("X-Replay-Stream-Length")
                    .map { it.toLongOrNull() ?: 0L }.orElse(0L),
            )
        } catch (e: Exception) {
            log.warn("replay chunk $stream@$offset failed", e)
            null
        }
    }

    /** The replays this player has kept, with the quota they count against. */
    fun fetchSavedReplays(): Fetch<ReplayListResponse> =
        authedGet("/v1/players/me/replays") {
            json.decodeFromString(ReplayListResponse.serializer(), it)
        }

    /** Keep this match's replay past the retention window; null on success. */
    fun saveReplay(matchId: String): String? = act("POST", "/v1/matches/$matchId/replay/save")

    fun unsaveReplay(matchId: String): String? = act("DELETE", "/v1/matches/$matchId/replay/save")

    fun fetchLeaderboardCategories(): Fetch<List<LeaderboardCategory>> =
        get("/v1/leaderboards") { json.decodeFromString(it) }

    fun fetchLeaderboardPage(id: String, limit: Int = 25, season: Int? = null): Fetch<LeaderboardResponse> {
        val seasonParam = season?.let { "&season=$it" } ?: ""
        return get("/v1/leaderboards/$id?limit=$limit$seasonParam") {
            json.decodeFromString(LeaderboardResponse.serializer(), it)
        }
    }

    /**
     * The party control socket. Long-lived and independent of any screen: it is
     * how invites arrive while the player is elsewhere in the menus, and how
     * every client in a party learns the leader changed something.
     */
    inner class PartySocket internal constructor(private val socket: WebSocket) {
        /**
         * The tail of the send chain.
         *
         * `WebSocket.sendText` may not be called again until the previous send
         * has completed — the JDK throws `IllegalStateException` on an
         * overlapping send. The party screens send in bursts (create a party,
         * then immediately invite into it), so an unchained send silently threw
         * away the second message every time. Composing each send onto the
         * previous future is what makes a burst arrive in order and in full.
         */
        private var tail: CompletableFuture<WebSocket> = CompletableFuture.completedFuture(socket)

        fun send(message: PartyClientMessage) {
            val text = runCatching {
                json.encodeToString(PartyClientMessage.serializer(), message)
            }.getOrElse {
                log.warn("could not encode $message", it)
                return
            }
            synchronized(this) {
                tail = tail
                    .thenCompose { ws -> ws.sendText(text, true) }
                    .exceptionally { error ->
                        // One failed send must not poison the chain: every later
                        // message would inherit the failure and never be sent.
                        log.warn("party send failed", error)
                        socket
                    }
            }
        }

        fun close() {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
        }
    }

    fun connectParty(
        onMessage: (PartyServerMessage) -> Unit,
        onClosed: (reason: String?) -> Unit,
    ): PartySocket? {
        // Reported rather than returned silently: every null from here has to
        // reach onClosed, or the caller is left holding a "connecting" state
        // that nothing will ever resolve.
        val token = session?.token ?: run {
            onClosed("not signed in")
            return null
        }
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/v1/party?token=$token"

        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    runCatching { json.decodeFromString(PartyServerMessage.serializer(), text) }
                        .onSuccess(onMessage)
                        .onFailure { log.warn("bad party message: $text", it) }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                onClosed(reason.ifEmpty { null })
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                log.warn("party socket error", error)
                onClosed(error.message)
            }
        }

        return try {
            val socket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), listener)
                .join()
            socket.sendText(
                json.encodeToString(PartyClientMessage.serializer(), PartyClientMessage.Hello),
                true,
            ).join()
            PartySocket(socket)
        } catch (e: Exception) {
            log.warn("party connect failed", e)
            onClosed(e.message ?: "connection failed")
            null
        }
    }

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
        /** Queue the whole party as one unit rather than the caller alone. */
        asParty: Boolean = false,
    ): QueueSocket? {
        val token = session?.token ?: run {
            onClosed("not signed in")
            return null
        }
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
                QueueClientMessage.JoinQueue(format, asParty = asParty),
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
