package dev.yabranked.backend.api

import dev.yabranked.backend.auth.SessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.MatchTeam
import dev.yabranked.proto.PlayerProfile
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.QueueClientMessage
import dev.yabranked.proto.QueueServerMessage
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** How long clients wait on the queue socket for a match server to come up. */
private const val PROVISION_TIMEOUT_NANOS = 180L * 1_000_000_000L

@Serializable
data class SessionRequest(val username: String, val serverId: String)

@Serializable
data class SessionResponse(val token: String, val profile: PlayerProfile)

/**
 * In-memory bearer tokens for authenticated players.
 * Replaced by persistent sessions when the client mod lands (Phase 3).
 */
class TokenRegistry {
    private val tokens = ConcurrentHashMap<String, UUID>()
    private val random = SecureRandom()

    fun issue(player: UUID): String {
        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        tokens[token] = player
        return token
    }

    fun resolve(token: String): UUID? = tokens[token]
}

class ApiDependencies(
    val verifier: SessionVerifier,
    val players: PlayerStore,
    val matches: dev.yabranked.backend.store.MatchStore,
    val matchService: MatchService,
    val queueService: QueueService,
    val tokens: TokenRegistry = TokenRegistry(),
    /**
     * Exposes GET /v1/debug/matches/{id}/token so the mock client can settle
     * matches without an orchestrator. Only enabled together with fake auth.
     */
    val debugEndpoints: Boolean = false,
)

@Serializable
data class ReadyRequest(val matchId: String)

fun Application.rankedApi(deps: ApiDependencies) {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    install(ContentNegotiation) { json(json) }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(json)
    }

    fun profileOf(uuid: UUID): PlayerProfile? {
        val record = deps.players.get(uuid) ?: return null
        return PlayerProfile(
            uuid = record.uuid.toString(),
            name = record.name,
            rating = record.rating,
            placementMatchesRemaining = deps.matchService.placementMatchesRemaining(record),
            wins = record.wins,
            losses = record.losses,
            draws = record.draws,
        )
    }

    routing {
        post("/v1/auth/session") {
            val request = call.receive<SessionRequest>()
            val verified = deps.verifier.verify(request.username, request.serverId)
            if (verified == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "session verification failed"))
                return@post
            }
            deps.matchService.getOrCreatePlayer(verified.uuid, verified.name)
            val token = deps.tokens.issue(verified.uuid)
            call.respond(SessionResponse(token, profileOf(verified.uuid)!!))
        }

        get("/v1/players/{uuid}") {
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            val profile = uuid?.let(::profileOf)
            if (profile == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
            } else {
                call.respond(profile)
            }
        }

        get("/v1/leaderboard") {
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
            val top = deps.players.topByRating(limit = limit, minMatches = 1)
                .mapNotNull { profileOf(it.uuid) }
            call.respond(top)
        }

        if (deps.debugEndpoints) {
            get("/v1/debug/matches/{id}/token") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                val match = id?.let { deps.matches.get(it) }
                if (match == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown match"))
                } else {
                    call.respond(mapOf("token" to match.serverToken))
                }
            }
        }

        // Agent: match server configured and waiting for players.
        post("/v1/internal/matches/ready") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrEmpty()) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing token"))
                return@post
            }
            val request = call.receive<ReadyRequest>()
            when (deps.matchService.markReady(request.matchId, token)) {
                MatchService.ReadyResult.Ok ->
                    call.respond(HttpStatusCode.OK, mapOf("status" to "ready"))
                MatchService.ReadyResult.BadToken ->
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad token"))
                MatchService.ReadyResult.UnknownMatch ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown match"))
            }
        }

        // Result report from the match-server agent, authenticated by per-match token.
        post("/v1/internal/matches/result") {
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrEmpty()) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing token"))
                return@post
            }
            val report = call.receive<MatchResultReport>()
            when (val result = deps.matchService.settle(report, token)) {
                is MatchService.SettleResult.Settled ->
                    call.respond(HttpStatusCode.OK, mapOf("status" to "settled"))
                MatchService.SettleResult.AlreadySettled ->
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "already settled"))
                MatchService.SettleResult.BadToken ->
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad token"))
                MatchService.SettleResult.UnknownMatch ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown match"))
            }
        }

        webSocket("/v1/queue") {
            val token = call.request.queryParameters["token"]
            val playerUuid = token?.let(deps.tokens::resolve)
            if (playerUuid == null) {
                sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("unauthorized"))
                close()
                return@webSocket
            }
            val player = deps.players.get(playerUuid)
            if (player == null) {
                sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("unknown player"))
                close()
                return@webSocket
            }

            val matched = java.util.concurrent.atomic.AtomicReference<MatchRecord?>(null)
            val listener: (UUID, MatchRecord) -> Unit = { uuid, record ->
                if (uuid == playerUuid) matched.set(record)
            }
            deps.queueService.onPlayerMatched(listener)

            try {
                val join = receiveDeserialized<QueueClientMessage>()
                if (join !is QueueClientMessage.JoinQueue) {
                    sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("expected join_queue"))
                    return@webSocket
                }
                deps.queueService.join(playerUuid, player.rating, join.format)

                // push queue state until matched or the client disconnects/leaves
                while (matched.get() == null) {
                    val snapshot = deps.queueService.snapshot(playerUuid)
                    if (snapshot != null) {
                        sendSerialized<QueueServerMessage>(
                            QueueServerMessage.QueueState(
                                position = snapshot.position,
                                playersInQueue = snapshot.playersInQueue,
                                waitedSeconds = snapshot.waitedSeconds,
                            )
                        )
                    } else if (matched.get() == null) {
                        // not in queue and not matched -> left elsewhere; end session
                        break
                    }
                    delay(1.seconds)
                }

                matched.get()?.let { record ->
                    // wait for the orchestrator to provision the server and the
                    // agent to report ready before telling clients where to go
                    var ready: MatchRecord? = null
                    val deadline = System.nanoTime() + PROVISION_TIMEOUT_NANOS
                    while (System.nanoTime() < deadline) {
                        val current = deps.matches.get(record.id)
                        if (current == null || current.status == MatchStatus.VOIDED) break
                        if (current.status == MatchStatus.ACTIVE && current.serverAddress != null) {
                            ready = current
                            break
                        }
                        delay(1.seconds)
                    }

                    if (ready == null) {
                        sendSerialized<QueueServerMessage>(
                            QueueServerMessage.QueueError("match server could not be provisioned; please queue again")
                        )
                        return@let
                    }

                    val isTeamA = ready.playerA == playerUuid
                    val opponentUuid = if (isTeamA) ready.playerB else ready.playerA
                    val opponent = deps.players.get(opponentUuid)
                    sendSerialized<QueueServerMessage>(
                        QueueServerMessage.MatchFound(
                            matchId = ready.id.toString(),
                            team = if (isTeamA) MatchTeam.TEAM_A else MatchTeam.TEAM_B,
                            opponent = PlayerRef(opponentUuid.toString(), opponent?.name ?: "?"),
                            serverAddress = ready.serverAddress!!,
                        )
                    )
                }
            } catch (_: ClosedReceiveChannelException) {
                // client disconnected
            } finally {
                deps.queueService.removeListener(listener)
                deps.queueService.leave(playerUuid)
            }
        }
    }
}
