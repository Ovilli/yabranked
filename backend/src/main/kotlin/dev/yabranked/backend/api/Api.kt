package dev.yabranked.backend.api

import dev.yabranked.backend.auth.SessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.Tier
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.ReportRecord
import dev.yabranked.backend.store.ReportStore
import dev.yabranked.proto.MatchHistoryEntry
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.MatchTeam
import dev.yabranked.proto.PlayerProfile
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.QueueClientMessage
import dev.yabranked.proto.QueueServerMessage
import dev.yabranked.proto.ReportRequest
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
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
data class SessionRequest(
    val username: String,
    val serverId: String,
    val clientVersion: String? = null,
)

/** Numeric-dotted version compare; unparseable segments count as 0. */
internal fun versionAtLeast(version: String, minimum: String): Boolean {
    val a = version.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    val b = minimum.split('.', '-', '+').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return true
}

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
    /**
     * Minimum ranked-client mod version allowed to authenticate; null
     * disables the gate (local dev / mock client).
     */
    val minClientVersion: String? = null,
    val seasons: SeasonService = SeasonService(),
    val reports: ReportStore = dev.yabranked.backend.store.InMemoryReportStore(),
    /** Shared secret for the admin endpoints; null disables them all. */
    val adminToken: String? = null,
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
        val record = deps.players.getPlayer(uuid) ?: return null
        val stats = deps.matchService.statsFor(uuid)
        val placements = deps.matchService.placementMatchesRemaining(stats)
        return PlayerProfile(
            uuid = record.uuid.toString(),
            name = record.name,
            rating = stats.rating,
            placementMatchesRemaining = placements,
            wins = stats.wins,
            losses = stats.losses,
            draws = stats.draws,
            tier = Tier.format(stats.rating, isPlaced = placements <= 0),
            season = stats.season,
            rank = deps.players.rankOf(uuid, stats.season, minMatches = 1),
        )
    }

    /** Player-token auth for endpoints acting on behalf of a player. */
    fun authedPlayer(call: io.ktor.server.application.ApplicationCall): UUID? =
        call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim()
            ?.let(deps.tokens::resolve)

    fun isAdmin(call: io.ktor.server.application.ApplicationCall): Boolean {
        val expected = deps.adminToken ?: return false
        val given = call.request.headers["X-Admin-Token"] ?: return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    routing {
        post("/v1/auth/session") {
            val request = call.receive<SessionRequest>()

            val minVersion = deps.minClientVersion
            if (minVersion != null &&
                (request.clientVersion == null || !versionAtLeast(request.clientVersion, minVersion))
            ) {
                call.respond(
                    HttpStatusCode.UpgradeRequired,
                    mapOf("error" to "ranked client $minVersion or newer required"),
                )
                return@post
            }

            val verified = deps.verifier.verify(request.username, request.serverId)
            if (verified == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "session verification failed"))
                return@post
            }
            val player = deps.matchService.getOrCreatePlayer(verified.uuid, verified.name)
            if (player.isBanned) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "account banned from ranked play"))
                return@post
            }
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
            val season = call.request.queryParameters["season"]?.toIntOrNull()
                ?: deps.seasons.currentSeason
            val top = deps.players.topByRating(season = season, limit = limit, minMatches = 1)
                .mapIndexed { index, stats ->
                    val record = deps.players.getPlayer(stats.uuid)
                    PlayerProfile(
                        rank = index + 1,
                        uuid = stats.uuid.toString(),
                        name = record?.name ?: "?",
                        rating = stats.rating,
                        placementMatchesRemaining = 0,
                        wins = stats.wins,
                        losses = stats.losses,
                        draws = stats.draws,
                        tier = Tier.format(stats.rating, isPlaced = true),
                        season = stats.season,
                    )
                }
            call.respond(top)
        }

        get("/v1/seasons/current") {
            call.respond(mapOf("season" to deps.seasons.currentSeason))
        }

        get("/v1/players/{uuid}/matches") {
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            if (uuid == null || deps.players.getPlayer(uuid) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
            val season = call.request.queryParameters["season"]?.toIntOrNull()
                ?: deps.seasons.currentSeason

            val history = deps.matches.historyFor(uuid, season, limit).map { match ->
                val isTeamA = match.playerA == uuid
                val opponentUuid = if (isTeamA) match.playerB else match.playerA
                val opponent = deps.players.getPlayer(opponentUuid)
                val result = when (match.outcome) {
                    MatchOutcome.VOID, null -> "void"
                    MatchOutcome.DRAW -> "draw"
                    MatchOutcome.TEAM_A_WIN -> if (isTeamA) "win" else "loss"
                    MatchOutcome.TEAM_B_WIN -> if (isTeamA) "loss" else "win"
                }
                MatchHistoryEntry(
                    matchId = match.id.toString(),
                    opponent = PlayerRef(opponentUuid.toString(), opponent?.name ?: "?"),
                    result = result,
                    ratingBefore = if (isTeamA) match.ratingABefore else match.ratingBBefore,
                    ratingAfter = if (isTeamA) match.ratingAAfter else match.ratingBAfter,
                    durationSeconds = match.durationSeconds,
                    completedAt = match.completedAt?.epochSecond,
                )
            }
            call.respond(history)
        }

        // Player report: accused is always the opponent in the given match.
        post("/v1/reports") {
            val reporter = authedPlayer(call)
            if (reporter == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
                return@post
            }
            val request = call.receive<ReportRequest>()
            val matchId = runCatching { UUID.fromString(request.matchId) }.getOrNull()
            val match = matchId?.let { deps.matches.get(it) }
            if (match == null || (match.playerA != reporter && match.playerB != reporter)) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such match for this player"))
                return@post
            }
            if (deps.reports.existsFor(match.id, reporter)) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "already reported"))
                return@post
            }
            val accused = if (match.playerA == reporter) match.playerB else match.playerA
            deps.reports.insert(
                ReportRecord(
                    id = UUID.randomUUID(),
                    matchId = match.id,
                    reporter = reporter,
                    accused = accused,
                    reason = request.reason.take(500),
                    createdAt = java.time.Instant.now(),
                )
            )
            call.respond(HttpStatusCode.OK, mapOf("status" to "reported"))
        }

        // --- Admin (shared-secret header; disabled unless adminToken is set) ---

        post("/v1/admin/seasons/advance") {
            if (!isAdmin(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
                return@post
            }
            call.respond(mapOf("season" to deps.seasons.advance()))
        }

        get("/v1/admin/reports") {
            if (!isAdmin(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            call.respond(deps.reports.list(limit).map { report ->
                mapOf(
                    "id" to report.id.toString(),
                    "matchId" to report.matchId.toString(),
                    "reporter" to report.reporter.toString(),
                    "accused" to report.accused.toString(),
                    "reason" to report.reason,
                    "createdAt" to report.createdAt.toString(),
                )
            })
        }

        post("/v1/admin/bans/{uuid}") {
            if (!isAdmin(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
                return@post
            }
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            val player = uuid?.let { deps.players.getPlayer(it) }
            if (player == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@post
            }
            deps.players.upsertPlayer(player.copy(bannedAt = java.time.Instant.now()))
            call.respond(mapOf("status" to "banned"))
        }

        delete("/v1/admin/bans/{uuid}") {
            if (!isAdmin(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
                return@delete
            }
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            val player = uuid?.let { deps.players.getPlayer(it) }
            if (player == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@delete
            }
            deps.players.upsertPlayer(player.copy(bannedAt = null))
            call.respond(mapOf("status" to "unbanned"))
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
            val player = deps.players.getPlayer(playerUuid)
            if (player == null) {
                sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("unknown player"))
                close()
                return@webSocket
            }
            if (player.isBanned) {
                sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("account banned from ranked play"))
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
                deps.queueService.join(playerUuid, deps.matchService.statsFor(playerUuid).rating, join.format)

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
                    val opponent = deps.players.getPlayer(opponentUuid)
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
