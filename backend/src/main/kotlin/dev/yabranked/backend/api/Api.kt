package dev.yabranked.backend.api

import dev.yabranked.backend.auth.SessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.profile.Backgrounds
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.Tier
import dev.yabranked.backend.security.RateLimiters
import dev.yabranked.backend.season.SeasonRollover
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.ReportRecord
import dev.yabranked.backend.store.ReportStore
import dev.yabranked.backend.store.StoreDispatchers
import dev.yabranked.proto.MatchHistoryEntry
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.MatchTeam
import dev.yabranked.proto.PlayerProfile
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.ProfileUpdate
import dev.yabranked.proto.QueueClientMessage
import dev.yabranked.proto.QueueServerMessage
import dev.yabranked.backend.achievement.AchievementDef
import dev.yabranked.proto.Achievement
import dev.yabranked.proto.ReportRequest
import dev.yabranked.proto.SessionRequest
import dev.yabranked.proto.SessionResponse
import dev.yabranked.proto.VersusRecord
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * How long clients wait on the queue socket for a match server to come up.
 * The orchestrator's reaper voids the match shortly after this (see
 * [dev.yabranked.backend.orchestrator.OrchestratorConfig.readyTimeout]) so a
 * client that gives up never leaves a container running behind it.
 */
private val PROVISION_TIMEOUT_NANOS =
    dev.yabranked.backend.match.MatchService.PROVISION_TIMEOUT_SECONDS * 1_000_000_000L

/**
 * Cap on the head-to-head matches counted for one pair. Two players meeting
 * more often than this in a season would still be summarised fairly; the limit
 * only stops one endpoint loading an unbounded number of rows.
 */
private const val VERSUS_WINDOW = 500

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

/**
 * In-memory bearer tokens for authenticated players.
 *
 * Tokens expire after [ttl] and are swept on issue, so a leaked token stops
 * working on its own and the map cannot be grown without bound by minting.
 * [revokeAll] is what makes a ban reach a player who is already signed in.
 */
class TokenRegistry(
    private val ttl: Duration = 12.hours,
    private val clock: Clock = Clock.systemUTC(),
) {
    private class Session(val player: UUID, val expiresAt: Instant)

    private val tokens = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    fun issue(player: UUID): String {
        sweep()
        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        tokens[token] = Session(player, clock.instant().plusMillis(ttl.inWholeMilliseconds))
        return token
    }

    fun resolve(token: String): UUID? {
        val session = tokens[token] ?: return null
        if (!session.expiresAt.isAfter(clock.instant())) {
            tokens.remove(token, session)
            return null
        }
        return session.player
    }

    fun revoke(token: String) {
        tokens.remove(token)
    }

    /** Drops every live session for [player]; used when an account is banned. */
    fun revokeAll(player: UUID) {
        tokens.entries.removeIf { it.value.player == player }
    }

    /** Live sessions; exposed so tests can assert expired ones are dropped. */
    val size: Int get() = tokens.size

    private fun sweep() {
        val now = clock.instant()
        tokens.entries.removeIf { !it.value.expiresAt.isAfter(now) }
    }
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
    val achievements: dev.yabranked.backend.store.AchievementStore =
        dev.yabranked.backend.store.InMemoryAchievementStore(),
    /** Shared secret for the admin endpoints; null disables them all. */
    val adminToken: String? = null,
    /** Carries the closing season's placed ratings into the new one on advance. */
    val rollover: SeasonRollover = SeasonRollover(players, matchService.ratingSystem),
    /** Request budgets for the endpoints worth hammering; see [RateLimiters]. */
    val rateLimits: RateLimiters = RateLimiters(),
    /**
     * Accept the queue socket's token from `?token=` as well as from the
     * Authorization header. Kept on by default for clients that predate the
     * header; a token in a URL is copied into every access and proxy log it
     * passes through, so turn it off once every client has been updated.
     */
    val allowQueryToken: Boolean = true,
    /**
     * Where blocking store calls run. Handlers dispatch to it rather than
     * doing JDBC on Ktor's event loop; see [StoreDispatchers].
     */
    val storeDispatcher: CoroutineDispatcher = StoreDispatchers.default,
)

@Serializable
data class ReadyRequest(val matchId: String)

fun Application.rankedApi(deps: ApiDependencies) {
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    install(ContentNegotiation) { json(json) }
    install(StatusPages) {
        // The unguarded call.receive() sites used to answer a malformed body
        // with a 500 and a stack trace, which is both wrong and a disclosure.
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed request body"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed request body"))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed request body"))
        }
        exception<Throwable> { call, cause ->
            call.application.log.error("unhandled error in ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal error"))
        }
    }
    install(WebSockets) {
        contentConverter = KotlinxWebsocketSerializationConverter(json)
        // A queue socket is idle between state pushes, so a client that
        // vanished (crash, laptop lid, NAT drop) looks exactly like one that is
        // still waiting. Pings make the socket fail fast, which is what
        // releases the player's queue entry.
        pingPeriod = 15.seconds
        timeout = 30.seconds
    }

    /**
     * Runs blocking store work off the event loop.
     *
     * One call per handler wherever possible, and never around only part of a
     * transaction: the JDBC connection is bound to the thread that opened it,
     * so a hop in the middle would split the transaction in two.
     */
    suspend fun <T> onStore(block: () -> T): T = withContext(deps.storeDispatcher) { block() }

    /**
     * Build a player's profile. When [redact] is set the player's own privacy
     * preferences are applied for a public viewer: a hidden flag drops the
     * country, a hidden rating zeroes the exact rating (and peak) while the
     * derived tier is still shown. Own-profile responses pass redact = false so
     * the player always sees their real numbers.
     */
    suspend fun profileOf(uuid: UUID, redact: Boolean = false): PlayerProfile? = onStore {
        val record = deps.players.getPlayer(uuid) ?: return@onStore null
        val stats = deps.matchService.statsFor(uuid)
        val placements = deps.matchService.placementMatchesRemaining(stats)
        val hideRating = redact && record.hideRating
        PlayerProfile(
            uuid = record.uuid.toString(),
            name = record.name,
            rating = if (hideRating) 0 else stats.rating,
            placementMatchesRemaining = placements,
            wins = stats.wins,
            losses = stats.losses,
            draws = stats.draws,
            tier = Tier.format(stats.rating, isPlaced = placements <= 0),
            season = stats.season,
            // same threshold the leaderboard uses, so a player is never shown a
            // rank they do not hold on the ladder
            rank = deps.players.rankOf(uuid, stats.season, minMatches = deps.matchService.placementMatches),
            country = if (redact && record.hideFlag) null else record.country,
            background = record.background,
            playtimeSeconds = stats.playtimeSeconds,
            forfeits = stats.forfeits,
            peakRating = if (hideRating) null else maxOf(stats.peakRating, stats.rating),
            hideFlag = record.hideFlag,
            hideRating = record.hideRating,
        )
    }

    /** Player-token auth for endpoints acting on behalf of a player. */
    fun authedPlayer(call: io.ktor.server.application.ApplicationCall): UUID? =
        call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim()
            ?.let(deps.tokens::resolve)

    /**
     * Rate-limit key for a caller. The direct peer, not a forwarded header:
     * anything the client can set is a key the client can rotate.
     */
    fun sourceOf(call: io.ktor.server.application.ApplicationCall): String =
        call.request.origin.remoteAddress

    /** Spends one unit of [limiter]'s budget, answering 429 when it is gone. */
    suspend fun throttled(
        call: io.ktor.server.application.ApplicationCall,
        limiter: dev.yabranked.backend.security.RateLimiter,
        key: String,
    ): Boolean {
        val decision = limiter.acquire(key)
        if (!decision.allowed) {
            call.response.headers.append("Retry-After", decision.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "rate limited"))
        }
        return !decision.allowed
    }

    fun isAdmin(call: io.ktor.server.application.ApplicationCall): Boolean {
        val expected = deps.adminToken ?: return false
        val given = call.request.headers["X-Admin-Token"] ?: return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    routing {
        post("/v1/auth/session") {
            // Two budgets, both before any work: this endpoint proxies straight
            // to Mojang's hasJoined (so it is an amplifier, and getting the
            // deployment throttled there takes ranked down with it) and every
            // success mints a token that lives in memory until it expires.
            if (throttled(call, deps.rateLimits.session, sourceOf(call))) return@post
            val request = call.receive<SessionRequest>()
            if (throttled(call, deps.rateLimits.sessionIdentity, request.username.lowercase())) return@post

            val minVersion = deps.minClientVersion
            val clientVersion = request.clientVersion
            if (minVersion != null &&
                (clientVersion == null || !versionAtLeast(clientVersion, minVersion))
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
            val player = onStore { deps.matchService.getOrCreatePlayer(verified.uuid, verified.name) }
            if (player.isBanned) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "account banned from ranked play"))
                return@post
            }
            val token = deps.tokens.issue(verified.uuid)
            call.respond(SessionResponse(token, profileOf(verified.uuid)!!))
        }

        // Edit your own profile (country flag, card background). Player-token auth.
        put("/v1/players/me") {
            val self = authedPlayer(call)
            if (self == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "not signed in"))
                return@put
            }
            val update = runCatching { call.receive<ProfileUpdate>() }.getOrNull()
            if (update == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed body"))
                return@put
            }
            // country: "" clears it; a value must be a 2-letter alpha code. Leave
            // unchanged when the field is absent (null).
            val newCountry: String? = when (val c = update.country) {
                null -> null // sentinel handled below
                "" -> null
                else -> c.lowercase().takeIf { it.length == 2 && it.all { ch -> ch in 'a'..'z' } }
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid country code"))
                        return@put
                    }
            }
            val record = onStore { deps.players.getPlayer(self) }
            if (record == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@put
            }
            // "country" present (even blank) → set/clear; absent → keep as-is.
            val country = if (update.country == null) record.country else newCountry
            // background: the id ends up in a client-side texture path, so only
            // ones we ship art for are allowed through.
            val background = when (val b = update.background) {
                null -> record.background
                else -> if (b.isBlank()) "default" else Backgrounds.normalize(b) ?: run {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "unknown background"))
                    return@put
                }
            }
            // Privacy toggles: null leaves the current setting untouched.
            val hideFlag = update.hideFlag ?: record.hideFlag
            val hideRating = update.hideRating ?: record.hideRating
            onStore {
                deps.players.upsertPlayer(
                    record.copy(
                        country = country,
                        background = background,
                        hideFlag = hideFlag,
                        hideRating = hideRating,
                    ),
                )
            }
            call.respond(profileOf(self)!!)
        }

        get("/v1/players/{uuid}") {
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            val profile = uuid?.let { profileOf(it, redact = true) }
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
            // Only placed players are ranked. Listing someone mid-placement
            // among established players meant one lucky win could show as rank
            // 1, and every row claimed isPlaced whatever its match count.
            val ladder = onStore {
                deps.players.leaderboard(
                    season = season,
                    limit = limit,
                    minMatches = deps.matchService.placementMatches,
                )
            }
            val top = ladder.mapIndexed { index, (stats, record) ->
                val placements = deps.matchService.placementMatchesRemaining(stats)
                PlayerProfile(
                    rank = index + 1,
                    uuid = stats.uuid.toString(),
                    name = record?.name ?: "?",
                    rating = stats.rating,
                    placementMatchesRemaining = placements,
                    wins = stats.wins,
                    losses = stats.losses,
                    draws = stats.draws,
                    tier = Tier.format(stats.rating, isPlaced = placements <= 0),
                    season = stats.season,
                    // Ranking needs the number, so hideRating is honoured on
                    // the profile/match-found reveal only — not here. The flag
                    // still hides the country.
                    country = if (record?.hideFlag == true) null else record?.country,
                    background = record?.background ?: "default",
                    hideFlag = record?.hideFlag ?: false,
                    hideRating = record?.hideRating ?: false,
                )
            }
            call.respond(top)
        }

        // Head-to-head record between two players, all seasons.
        get("/v1/players/{a}/versus/{b}") {
            val a = runCatching { UUID.fromString(call.parameters["a"]) }.getOrNull()
            val b = runCatching { UUID.fromString(call.parameters["b"]) }.getOrNull()
            if (a == null || b == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@get
            }

            // the store returns only the decided meetings; scanning a wide
            // window of A's history in memory found the same rows the hard way
            val meetings = onStore {
                deps.matches.between(a, b, deps.seasons.currentSeason, limit = VERSUS_WINDOW)
            }

            var wins = 0
            var losses = 0
            var draws = 0
            for (match in meetings) {
                val aIsTeamA = match.playerA == a
                when (match.outcome) {
                    MatchOutcome.DRAW -> draws++
                    MatchOutcome.TEAM_A_WIN -> if (aIsTeamA) wins++ else losses++
                    MatchOutcome.TEAM_B_WIN -> if (aIsTeamA) losses++ else wins++
                    else -> {}
                }
            }
            call.respond(VersusRecord(wins = wins, losses = losses, draws = draws))
        }

        // Milestones this player has unlocked, oldest first. Title/description
        // are resolved from the server catalog so the client ships no copy of it;
        // ids no longer in the catalog are dropped rather than shown blank.
        get("/v1/players/{uuid}/achievements") {
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            val earned = uuid?.let {
                onStore {
                    if (deps.players.getPlayer(it) == null) null else deps.achievements.earned(it)
                }
            }
            if (earned == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@get
            }
            call.respond(
                earned.mapNotNull { rec ->
                    val def = AchievementDef.byId(rec.achievementId) ?: return@mapNotNull null
                    Achievement(def.id, def.title, def.description, rec.earnedAt.toEpochMilli())
                }.sortedBy { it.earnedAt }
            )
        }

        get("/v1/seasons/current") {
            call.respond(mapOf("season" to deps.seasons.currentSeason))
        }

        get("/v1/players/{uuid}/matches") {
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 10
            val season = call.request.queryParameters["season"]?.toIntOrNull()
                ?: deps.seasons.currentSeason

            // one hop, and the opponents' names come back in a single lookup
            // rather than one per row
            val page = uuid?.let {
                onStore {
                    if (deps.players.getPlayer(it) == null) return@onStore null
                    val records = deps.matches.historyFor(it, season, limit)
                    val opponents = deps.players.getPlayers(
                        records.map { match -> if (match.playerA == it) match.playerB else match.playerA }
                    )
                    records to opponents
                }
            }
            if (page == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@get
            }
            val (records, opponents) = page

            val history = records.map { match ->
                val isTeamA = match.playerA == uuid
                val opponentUuid = if (isTeamA) match.playerB else match.playerA
                val opponent = opponents[opponentUuid]
                val result = when (match.outcome) {
                    MatchOutcome.VOID, null -> "void"
                    MatchOutcome.DRAW -> "draw"
                    MatchOutcome.TEAM_A_WIN -> if (isTeamA) "win" else "loss"
                    MatchOutcome.TEAM_B_WIN -> if (isTeamA) "loss" else "win"
                }
                MatchHistoryEntry(
                    matchId = match.id.toString(),
                    opponent = PlayerRef(
                        opponentUuid.toString(),
                        opponent?.name ?: "?",
                        country = if (opponent?.hideFlag == true) null else opponent?.country,
                    ),
                    result = result,
                    ratingBefore = if (isTeamA) match.ratingABefore else match.ratingBBefore,
                    ratingAfter = if (isTeamA) match.ratingAAfter else match.ratingBAfter,
                    opponentRating = if (isTeamA) match.ratingBBefore else match.ratingABefore,
                    durationSeconds = match.durationSeconds,
                    completedAt = match.completedAt?.epochSecond,
                    yourScore = if (isTeamA) match.teamAScore else match.teamBScore,
                    opponentScore = if (isTeamA) match.teamBScore else match.teamAScore,
                    worldSeed = match.settings.worldSeed,
                    cardSeed = match.settings.cardSeed,
                    wasForfeit = match.forfeitedBy != null,
                    forfeitedByYou = match.forfeitedBy == uuid,
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
            val match = matchId?.let { onStore { deps.matches.get(it) } }
            if (match == null || (match.playerA != reporter && match.playerB != reporter)) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such match for this player"))
                return@post
            }
            val accused = if (match.playerA == reporter) match.playerB else match.playerA
            val filed = onStore {
                if (deps.reports.existsFor(match.id, reporter)) return@onStore false
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
                true
            }
            if (!filed) {
                call.respond(HttpStatusCode.Conflict, mapOf("error" to "already reported"))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("status" to "reported"))
        }

        // --- Admin (shared-secret header; disabled unless adminToken is set) ---

        post("/v1/admin/seasons/advance") {
            if (!isAdmin(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
                return@post
            }
            // Advancing used to just bump the number and abandon the old ladder.
            // The closing season's rows are left untouched — they are its final
            // standings, readable at ?season=N forever.
            val from = deps.seasons.currentSeason
            val to = onStore {
                val next = deps.seasons.advance()
                deps.rollover.roll(from, next)
                next
            }
            call.respond(mapOf("season" to to, "previousSeason" to from))
        }

        get("/v1/admin/reports") {
            if (!isAdmin(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
                return@get
            }
            val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
            call.respond(onStore { deps.reports.list(limit) }.map { report ->
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
            val banned = uuid != null && onStore {
                val player = deps.players.getPlayer(uuid) ?: return@onStore false
                deps.players.upsertPlayer(player.copy(bannedAt = java.time.Instant.now()))
                true
            }
            if (uuid == null || !banned) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@post
            }
            // A ban used to do nothing to a player who was already connected —
            // it only bit at their next sign-in. Cut the live session, take them
            // out of the queue, and void whatever match they are in.
            deps.tokens.revokeAll(uuid)
            deps.queueService.leave(uuid)
            onStore {
                for (match in deps.matches.unsettled()) {
                    if (match.playerA == uuid || match.playerB == uuid) {
                        deps.matchService.voidMatch(match.id)
                    }
                }
            }
            call.respond(mapOf("status" to "banned"))
        }

        delete("/v1/admin/bans/{uuid}") {
            if (!isAdmin(call)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
                return@delete
            }
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            val unbanned = uuid?.let {
                onStore {
                    val player = deps.players.getPlayer(it) ?: return@onStore false
                    deps.players.upsertPlayer(player.copy(bannedAt = null))
                    true
                }
            }
            if (unbanned != true) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@delete
            }
            call.respond(mapOf("status" to "unbanned"))
        }

        if (deps.debugEndpoints) {
            get("/v1/debug/matches/{id}/token") {
                val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
                val match = id?.let { onStore { deps.matches.get(it) } }
                if (match == null) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown match"))
                } else {
                    call.respond(mapOf("token" to match.serverToken))
                }
            }
        }

        // Agent: match server configured and waiting for players.
        post("/v1/internal/matches/ready") {
            if (throttled(call, deps.rateLimits.internal, sourceOf(call))) return@post
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrEmpty()) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing token"))
                return@post
            }
            val request = call.receive<ReadyRequest>()
            // one hop for the whole call: markReady is a transaction
            when (onStore { deps.matchService.markReady(request.matchId, token) }) {
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
            // the per-match token is the only thing guarding this endpoint, so
            // cap how fast one source may guess at it
            if (throttled(call, deps.rateLimits.internal, sourceOf(call))) return@post
            val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
            if (token.isNullOrEmpty()) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "missing token"))
                return@post
            }
            val report = call.receive<MatchResultReport>()
            // likewise: settle's transaction must not be split across threads
            when (val result = onStore { deps.matchService.settle(report, token) }) {
                is MatchService.SettleResult.Settled ->
                    call.respond(HttpStatusCode.OK, mapOf("status" to "settled"))
                is MatchService.SettleResult.InvalidReport ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.reason))
                MatchService.SettleResult.AlreadySettled ->
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "already settled"))
                MatchService.SettleResult.BadToken ->
                    call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad token"))
                MatchService.SettleResult.UnknownMatch ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown match"))
            }
        }

        webSocket("/v1/queue") {
            // Authorization header first: a token in the query string is copied
            // into every access log, proxy log and process listing the URL
            // touches. ?token= stays accepted only while allowQueryToken is on.
            val token = call.request.headers["Authorization"]
                ?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
                ?: call.request.queryParameters["token"]?.takeIf { deps.allowQueryToken }
            val playerUuid = token?.let(deps.tokens::resolve)
            if (playerUuid == null) {
                sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("unauthorized"))
                close()
                return@webSocket
            }
            val player = onStore { deps.players.getPlayer(playerUuid) }
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

            // Only the socket that actually enqueued this player may dequeue
            // them on the way out — otherwise a second socket for the same
            // account kicks the first one out of the queue when it closes.
            var ownsQueueEntry = false
            val leaveRequested = java.util.concurrent.atomic.AtomicBoolean(false)

            try {
                val join = receiveDeserialized<QueueClientMessage>()
                if (join !is QueueClientMessage.JoinQueue) {
                    sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("expected join_queue"))
                    return@webSocket
                }
                ownsQueueEntry = deps.queueService.join(
                    playerUuid,
                    onStore { deps.matchService.statsFor(playerUuid).rating },
                    join.format,
                )
                if (!ownsQueueEntry) {
                    sendSerialized<QueueServerMessage>(
                        QueueServerMessage.QueueError("already queued in another session")
                    )
                    return@webSocket
                }

                // Read the rest of the client's messages concurrently: the
                // push loop below never yields to receive, so leave_queue would
                // otherwise sit unread until the socket closed.
                val reader = launch {
                    try {
                        while (true) {
                            if (receiveDeserialized<QueueClientMessage>() is QueueClientMessage.LeaveQueue) {
                                leaveRequested.set(true)
                                return@launch
                            }
                        }
                    } catch (_: ClosedReceiveChannelException) {
                        // socket closed; the push loop notices via its own send
                    } catch (_: Exception) {
                        // malformed frame — ignore, the client can retry
                    }
                }

                // push queue state until matched or the client disconnects/leaves
                while (matched.get() == null && !leaveRequested.get()) {
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
                        ownsQueueEntry = false
                        break
                    }
                    delay(1.seconds)
                }
                reader.cancel()
                if (leaveRequested.get()) {
                    deps.queueService.leave(playerUuid)
                    ownsQueueEntry = false
                    return@webSocket
                }

                matched.get()?.let { record ->
                    // wait for the orchestrator to provision the server and the
                    // agent to report ready before telling clients where to go
                    var ready: MatchRecord? = null
                    val deadline = System.nanoTime() + PROVISION_TIMEOUT_NANOS
                    val pairedAt = System.nanoTime()
                    while (System.nanoTime() < deadline) {
                        // Keep talking while the container boots. Silence here
                        // read as a stalled search: the client had no way to
                        // tell "paired, server starting" from "still looking".
                        sendSerialized<QueueServerMessage>(
                            QueueServerMessage.QueueState(
                                position = 0,
                                playersInQueue = 0,
                                waitedSeconds = (System.nanoTime() - pairedAt) / 1_000_000_000L,
                                preparingMatch = true,
                            )
                        )
                        val current = onStore { deps.matches.get(record.id) }
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
                    val opponent = onStore { deps.players.getPlayer(opponentUuid) }
                    val opponentProfile = profileOf(opponentUuid, redact = true)
                    sendSerialized<QueueServerMessage>(
                        QueueServerMessage.MatchFound(
                            matchId = ready.id.toString(),
                            team = if (isTeamA) MatchTeam.TEAM_A else MatchTeam.TEAM_B,
                            opponent = PlayerRef(
                                opponentUuid.toString(),
                                opponent?.name ?: "?",
                                country = if (opponent?.hideFlag == true) null else opponent?.country,
                            ),
                            serverAddress = ready.serverAddress!!,
                            // redacted profile already zeroes a hidden rating; tier stays.
                            opponentRating = opponentProfile?.rating ?: 0,
                            opponentTier = opponentProfile?.tier ?: "Unranked",
                        )
                    )
                }
            } catch (_: ClosedReceiveChannelException) {
                // client disconnected
            } finally {
                deps.queueService.removeListener(listener)
                // matched players were already dequeued by the tick that paired
                // them; leaving here would only dequeue someone else's entry
                if (ownsQueueEntry && matched.get() == null) deps.queueService.leave(playerUuid)
            }
        }
    }
}
