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
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchSide
import dev.yabranked.proto.ModeStats
import dev.yabranked.proto.PresenceState
import dev.yabranked.proto.PrivacySettings
import dev.yabranked.proto.Visibility
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.WebsocketDeserializeException
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
import kotlinx.coroutines.NonCancellable
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

/**
 * Seconds a party member's queue socket tolerates not being in the queue before
 * it gives up. Their socket and the leader's join race, and hanging up on the
 * loser of that race would leave one member of a matched party never told where
 * to connect.
 */
private const val OBSERVER_GRACE_TICKS = 5

/**
 * Whether [cause] means "that frame was not something this build can decode"
 * rather than "this socket is gone" — the difference between skipping a frame
 * and giving up on the connection.
 *
 * Two types, because Ktor's converter only raises its own for a frame of the
 * wrong *shape*; a payload the serializer refuses (an unknown `type`
 * discriminator, say, which is what a newer client's message looks like) comes
 * straight out of kotlinx as a [SerializationException].
 */
internal fun isUndecodableFrame(cause: Throwable): Boolean =
    cause is WebsocketDeserializeException || cause is SerializationException

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
    /** Match recordings; see [replayApi] for who may read one. */
    val replays: dev.yabranked.backend.store.ReplayStore =
        dev.yabranked.backend.store.InMemoryReplayStore(),
    /** The packet bytes those recordings consist of, which are not in the database. */
    val replayBlobs: dev.yabranked.backend.store.ReplayBlobStore =
        dev.yabranked.backend.store.InMemoryReplayBlobStore(),
    /** How many replays a player may keep and how long the rest survive. */
    val replayPolicy: dev.yabranked.backend.store.ReplayPolicy =
        dev.yabranked.backend.store.ReplayPolicy(),
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

    // --- social ---
    // Defaulted to in-memory so every existing construction site (and every
    // test) keeps compiling and gets a working, if non-persistent, social layer.
    val presence: dev.yabranked.backend.social.Presence = dev.yabranked.backend.social.Presence(),
    val friendStore: dev.yabranked.backend.store.FriendStore =
        dev.yabranked.backend.store.InMemoryFriendStore(),
    val endorsementStore: dev.yabranked.backend.store.EndorsementStore =
        dev.yabranked.backend.store.InMemoryEndorsementStore(),
    val friends: dev.yabranked.backend.social.FriendService =
        dev.yabranked.backend.social.FriendService(friendStore, players, matches, seasons),
    val endorsements: dev.yabranked.backend.social.EndorsementService =
        dev.yabranked.backend.social.EndorsementService(endorsementStore, matches),
) {
    /**
     * The party registry. Built here rather than injected because it needs a
     * view of the player stores that only this object can assemble, and because
     * every route and the queue socket must share exactly one instance.
     */
    val parties: dev.yabranked.backend.social.PartyService =
        dev.yabranked.backend.social.PartyService(
            lookup = { uuid, format ->
                val record = players.getPlayer(uuid)
                if (record == null) null else {
                    val stats = matchService.statsFor(uuid)
                    val rating = matchService.ratingFor(uuid, format)
                    dev.yabranked.backend.social.PartyPlayerSnapshot(
                        ref = PlayerRef(
                            uuid = record.uuid.toString(),
                            name = record.name,
                            country = record.country.takeIf { record.privacy.showCountry == Visibility.EVERYONE },
                        ),
                        rating = rating,
                        tier = Tier.format(
                            rating,
                            isPlaced = matchService.placementMatchesRemaining(stats) <= 0,
                        ),
                        hideRating = record.privacy.showRating == Visibility.NOBODY,
                        allowInvites = record.privacy.allowPartyInvites,
                        friendsOnly = record.privacy.partyInvitesFromFriendsOnly,
                        banned = record.isBanned,
                    )
                }
            },
            presence = presence,
            areFriends = friends::areFriends,
        )
}

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

    /** The per-mode breakdown, newest ladders included, sorted by time spent. */
    fun modeStatsOf(uuid: UUID, showRating: Boolean): List<ModeStats> =
        deps.matchService.modesFor(uuid)
            .filter { it.matchesPlayed > 0 }
            .sortedByDescending { it.playtimeSeconds }
            .map { row ->
                // Only rated modes have a meaningful ladder position; a casual
                // mode's rating column exists to keep the row shape uniform and
                // must never be rendered as if it were one.
                val rated = row.format.ranked && showRating
                ModeStats(
                    format = row.format,
                    matchesPlayed = row.matchesPlayed,
                    wins = row.wins,
                    losses = row.losses,
                    draws = row.draws,
                    playtimeSeconds = row.playtimeSeconds,
                    rating = row.rating.takeIf { rated },
                    tier = if (rated) {
                        Tier.format(
                            row.rating,
                            isPlaced = row.matchesPlayed >= deps.matchService.teamPlacementMatches,
                        )
                    } else null,
                    currentStreak = row.currentStreak,
                    bestStreak = row.bestStreak,
                )
            }

    /**
     * Build a player's profile as [viewer] is allowed to see it.
     *
     * Every gated field goes through the subject's own [PrivacySettings]: the
     * viewer's relationship to them (self, accepted friend, stranger) is
     * resolved once and each [Visibility] decides its own field. A hidden
     * rating still shows the derived tier — hiding the ladder position entirely
     * would break matchmaking transparency — and everything else simply
     * disappears rather than showing a stale or zeroed value.
     *
     * [redact] alone (no viewer) means "a stranger is asking", which is what
     * the anonymous endpoints pass.
     */
    suspend fun profileOf(uuid: UUID, redact: Boolean = false, viewer: UUID? = null): PlayerProfile? = onStore {
        val record = deps.players.getPlayer(uuid) ?: return@onStore null
        val stats = deps.matchService.statsFor(uuid)
        val placements = deps.matchService.placementMatchesRemaining(stats)

        val isSelf = !redact || viewer == uuid
        val isFriend = viewer != null && viewer != uuid && deps.friends.areFriends(viewer, uuid)
        val privacy = record.privacy
        fun visible(field: Visibility) = field.allows(isSelf, isFriend)

        val showRating = visible(privacy.showRating)
        val showStreak = visible(privacy.showStreak)
        val tier = Tier.format(stats.rating, isPlaced = placements <= 0)

        PlayerProfile(
            uuid = record.uuid.toString(),
            name = record.name,
            rating = if (showRating) stats.rating else 0,
            placementMatchesRemaining = placements,
            wins = stats.wins,
            losses = stats.losses,
            draws = stats.draws,
            tier = tier,
            season = stats.season,
            // same threshold the leaderboard uses, so a player is never shown a
            // rank they do not hold on the ladder
            rank = deps.players.rankOf(uuid, stats.season, minMatches = deps.matchService.placementMatches),
            country = record.country.takeIf { visible(privacy.showCountry) },
            background = record.background,
            playtimeSeconds = if (visible(privacy.showPlaytime)) stats.playtimeSeconds else 0,
            forfeits = stats.forfeits,
            peakRating = if (showRating) maxOf(stats.peakRating, stats.rating) else null,
            hideFlag = record.hideFlag,
            hideRating = record.hideRating,
            // Own profile carries the real settings so the client can restore
            // its toggles; a stranger gets the defaults, since the settings are
            // themselves information about the account.
            privacy = if (isSelf) privacy else PrivacySettings(),
            modes = if (visible(privacy.showPlaytime)) modeStatsOf(uuid, showRating) else emptyList(),
            currentStreak = if (showStreak) deps.matchService.winStreakOf(uuid) else null,
            bestStreak = if (showStreak) deps.matchService.bestStreakOf(uuid) else null,
            endorsement = if (visible(privacy.showEndorsements)) deps.endorsements.summaryFor(uuid) else null,
            onlineStatus = deps.presence.stateOf(uuid).wire
                .takeIf { visible(privacy.showOnlineStatus) && it != PresenceState.OFFLINE.wire },
            isFriend = isFriend,
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

    /**
     * Live queue sockets, so the party layer can tell a waiting client its
     * search is over. Keyed by player: one queue socket per account.
     */
    val queueCancelSinks = java.util.concurrent.ConcurrentHashMap<UUID, (String) -> Unit>()

    // A party whose roster changed under it cannot keep its queue entry: the
    // match would be built a player short. PartyService decides that; this drops
    // the ticket and tells everyone who was waiting on it.
    deps.parties.onQueueCancelled { partyId, members, reason ->
        launch {
            deps.queueService.leaveParty(partyId)
            for (member in members) queueCancelSinks[member]?.invoke(reason)
        }
    }

    fun isAdmin(call: io.ktor.server.application.ApplicationCall): Boolean {
        val expected = deps.adminToken ?: return false
        val given = call.request.headers["X-Admin-Token"] ?: return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    routing {
        // friends, parties, endorsements and the per-mode leaderboards
        socialApi(deps)
        // match recordings: agent upload, playback, saving, moderator review
        replayApi(deps)

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
            // Read and write are one transaction over one locked row.
            // upsertPlayer replaces every column, so reading the record in one
            // dispatcher hop and writing it back in a later one put back
            // whatever landed in between — an admin ban issued while the player
            // sat on their settings screen was simply erased by their next save.
            val failure: Pair<HttpStatusCode, String>? = onStore {
                deps.matchService.transactionRunner.transaction {
                    val record = deps.players.getPlayerForUpdate(self)
                        ?: return@transaction HttpStatusCode.NotFound to "unknown player"
                    // background: the id ends up in a client-side texture path,
                    // so only ones we ship art for are allowed through.
                    val background = when (val b = update.background) {
                        null -> record.background
                        else -> if (b.isBlank()) "default" else Backgrounds.normalize(b)
                            ?: return@transaction HttpStatusCode.BadRequest to "unknown background"
                    }
                    // "country" present (even blank) → set/clear; absent → keep as-is.
                    val country = if (update.country == null) record.country else newCountry
                    // Privacy. The whole block wins when the client sends one; the two
                    // legacy booleans are folded into it otherwise, so an old client and
                    // a new one can never leave the two views disagreeing.
                    val privacy = update.privacy ?: record.privacy.copy(
                        showCountry = when (update.hideFlag) {
                            null -> record.privacy.showCountry
                            true -> Visibility.NOBODY
                            false -> Visibility.EVERYONE
                        },
                        showRating = when (update.hideRating) {
                            null -> record.privacy.showRating
                            true -> Visibility.NOBODY
                            false -> Visibility.EVERYONE
                        },
                    )
                    deps.players.upsertPlayer(
                        record.copy(country = country, background = background).withPrivacy(privacy),
                    )
                    null
                }
            }
            if (failure != null) {
                call.respond(failure.first, mapOf("error" to failure.second))
                return@put
            }
            call.respond(profileOf(self)!!)
        }

        get("/v1/players/{uuid}") {
            val uuid = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
            // Signing the request in is optional, but it is what lets a friend
            // see the friends-only fields.
            val profile = uuid?.let { profileOf(it, redact = true, viewer = authedPlayer(call)) }
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

            val viewer = authedPlayer(call)
            // one hop, and every participant's name comes back in a single
            // lookup rather than one per row
            val page = uuid?.let {
                onStore {
                    val record = deps.players.getPlayer(it) ?: return@onStore null
                    val isSelf = viewer == it
                    val isFriend = viewer != null && !isSelf && deps.friends.areFriends(viewer, it)
                    if (!record.privacy.showMatchHistory.allows(isSelf, isFriend)) {
                        return@onStore Triple(emptyList(), emptyMap(), false)
                    }
                    val records = deps.matches.historyFor(it, season, limit)
                    // every side of every row, not just the two captains
                    val people = deps.players.getPlayers(records.flatMap { match -> match.participants })
                    Triple(records, people, isSelf)
                }
            }
            if (page == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
                return@get
            }
            val (records, people, isSelf) = page

            fun refOf(other: UUID): PlayerRef {
                val record = people[other]
                return PlayerRef(
                    other.toString(),
                    record?.name ?: "?",
                    country = record?.country.takeIf { record?.privacy?.showCountry == Visibility.EVERYONE },
                )
            }

            val history = records.map { match ->
                val isTeamA = match.sideOf(uuid!!) == 0
                val enemies = match.opponentsOf(uuid)
                val opponentUuid = enemies.firstOrNull() ?: if (isTeamA) match.playerB else match.playerA
                val result = when {
                    match.outcome == null || match.outcome == MatchOutcome.VOID -> "void"
                    match.outcome == MatchOutcome.DRAW -> "draw"
                    match.didWin(uuid) -> "win"
                    else -> "loss"
                }
                MatchHistoryEntry(
                    matchId = match.id.toString(),
                    opponent = refOf(opponentUuid),
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
                    format = match.format,
                    // Both halves matter: a casual format is never rated, and a
                    // rated format played unrated by a party is not either.
                    rated = match.rated && match.format.ranked,
                    teammates = match.teammatesOf(uuid).map(::refOf),
                    opponents = enemies.map(::refOf),
                    // only ever offered on your own history: endorsing is an
                    // action, and nobody else can take it for you
                    canEndorse = isSelf && deps.endorsements.canEndorse(uuid, match.id),
                )
            }
            call.respond(history)
        }

        /*
         * The match this player is in right now, or 204 when there is none.
         *
         * The client's idea of "I am in a match" is set when it is told about
         * one and cleared when it is disconnected from the match server. That
         * covers the ordinary path and nothing else: a match that ends while the
         * player is sitting in the menus — a teammate forfeiting, the
         * orchestrator reaping a server that never came up — produces no
         * disconnect, so the client went on believing the match was live and
         * offered nothing but "Forfeit". This is the one question that unwedges
         * it, and it recovers a client that was restarted mid-match too.
         */
        get("/v1/players/me/match") {
            val player = authedPlayer(call)
            if (player == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
                return@get
            }
            val live = onStore { deps.matches.liveFor(player) }
            if (live == null) {
                call.respond(HttpStatusCode.NoContent)
                return@get
            }
            // The same payload the queue socket pushes, from the same builder:
            // a client that reconnects into a running match must see exactly
            // what it would have seen had it never left.
            call.respond(onStore { buildMatchFound(deps, player, live) })
        }

        /*
         * Concede a match from the client.
         *
         * The match server has a `/forfeit` command, but reaching it means being
         * connected to the container. A player who crashed out, alt-F4'd, or
         * simply backed out to the ranked menu cannot, and their opponent was
         * left waiting out the abandon timer for a match both of them had
         * already finished with. This is the same act over the one channel that
         * is always available.
         *
         * Nothing here can express anything but "I lose", so participation is
         * the whole authorization.
         */
        post("/v1/matches/{id}/forfeit") {
            val player = authedPlayer(call)
            if (player == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
                return@post
            }
            val matchId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
            if (matchId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed match id"))
                return@post
            }
            when (val result = onStore { deps.matchService.forfeit(matchId, player) }) {
                is MatchService.SettleResult.Settled ->
                    call.respond(HttpStatusCode.OK, mapOf("status" to "forfeited"))
                // Both read as "not your match" on the wire: telling a stranger
                // which match ids exist is not something this endpoint owes them.
                MatchService.SettleResult.UnknownMatch,
                MatchService.SettleResult.NotAParticipant ->
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such match for this player"))
                // Racing the agent's own report is the normal case when a player
                // quits the container: whoever lands first decides, and the
                // outcome is the same either way.
                MatchService.SettleResult.AlreadySettled ->
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "that match is already over"))
                MatchService.SettleResult.BadToken ->
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "that match is already over"))
                is MatchService.SettleResult.InvalidReport ->
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to result.reason))
            }
        }

        /*
         * Player report.
         *
         * Two ways in, one rule: the accused is whoever the *match roster* says
         * was on the other side. The client may name them, but only to pick one
         * opponent out of a team — a uuid that is not an opponent of the
         * reporter in that match is refused rather than recorded.
         *
         * With no match id the report came from a profile, so the backend finds
         * the most recent decided match the two shared. That is what makes
         * "report this player" reachable from anywhere they are visible, instead
         * of only from the screen that happens to follow their last match.
         */
        post("/v1/reports") {
            val reporter = authedPlayer(call)
            if (reporter == null) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
                return@post
            }
            val request = call.receive<ReportRequest>()
            val namedAccused = request.accused?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val matchId = request.matchId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (matchId == null && namedAccused == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "a match id or an accused player is required"))
                return@post
            }

            val match = onStore {
                if (matchId != null) deps.matches.get(matchId)
                // Newest shared match, across every mode: the thing being
                // reported is behaviour, and it does not stop being reportable
                // because it happened in a casual game.
                else deps.matches
                    .between(reporter, namedAccused!!, deps.seasons.currentSeason, limit = 1)
                    .firstOrNull()
            }
            // Membership through the roster, not playerA/playerB: those are only
            // each side's first player once teams exist, so four of a 3v3's six
            // players could not file a report at all.
            if (match == null || match.sideOf(reporter) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such match for this player"))
                return@post
            }
            val opponents = match.opponentsOf(reporter)
            val accused = when {
                namedAccused != null && namedAccused in opponents -> namedAccused
                namedAccused != null ->
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "that player was not on the other side of this match"),
                    )
                else -> opponents.firstOrNull()
            }
            if (accused == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such match for this player"))
                return@post
            }
            val filed = onStore {
                if (deps.reports.existsFor(match.id, reporter, accused)) return@onStore false
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
                // A reported match's recording is what a moderator will actually
                // judge the accusation on, so it stops being subject to the
                // retention sweep the moment the report exists — and stays that
                // way whatever the players do with their own copies.
                deps.replays.setUnderReview(match.id, true)
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
            // Same locked read-modify-write as the profile editor: a ban that
            // races the player's own save must not be the one that loses.
            val banned = uuid != null && onStore {
                deps.matchService.transactionRunner.transaction {
                    val player = deps.players.getPlayerForUpdate(uuid) ?: return@transaction false
                    deps.players.upsertPlayer(player.copy(bannedAt = java.time.Instant.now()))
                    true
                }
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
                    deps.matchService.transactionRunner.transaction {
                        val player = deps.players.getPlayerForUpdate(it) ?: return@transaction false
                        deps.players.upsertPlayer(player.copy(bannedAt = null))
                        true
                    }
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
                // Only the player-facing forfeit endpoint can produce this; an
                // agent authenticates with the match's own token, not as a
                // participant.
                MatchService.SettleResult.NotAParticipant ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "not a participant"))
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
            /** This socket belongs to a party member who is not the leader. */
            var observing = false
            /** The party whose ticket this socket is riding, leader or not. */
            var queuedParty: UUID? = null
            val leaveRequested = java.util.concurrent.atomic.AtomicBoolean(false)

            // The party layer cancels a search when the roster changes under it
            // (the leader left, a member dropped). Without this the client would
            // sit on a spinner for a queue entry that no longer exists.
            val cancelled = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val cancelSink: (String) -> Unit = { reason -> cancelled.set(reason) }
            queueCancelSinks[playerUuid] = cancelSink

            try {
                val join = receiveDeserialized<QueueClientMessage>()
                if (join !is QueueClientMessage.JoinQueue) {
                    sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("expected join_queue"))
                    return@webSocket
                }
                if (!join.format.playable || join.format.partyOnly) {
                    sendSerialized<QueueServerMessage>(
                        QueueServerMessage.QueueError("that mode cannot be queued for")
                    )
                    return@webSocket
                }

                // A party queues as one ticket so it is never split across two
                // matches, and only its leader may push the button.
                val partyId = if (join.asParty) deps.parties.partyIdOf(playerUuid) else null
                if (join.asParty && partyId == null) {
                    sendSerialized<QueueServerMessage>(QueueServerMessage.QueueError("you are not in a party"))
                    return@webSocket
                }
                // Only the leader's socket enqueues; every other member's socket
                // rides along on the same ticket, watching for the match. They
                // must not be refused — a member with no socket would never be
                // told where the match server is.
                observing = partyId != null && !deps.parties.isLeader(playerUuid)

                ownsQueueEntry = if (partyId == null || observing) {
                    if (observing) false else deps.queueService.join(
                        playerUuid,
                        onStore { deps.matchService.ratingFor(playerUuid, join.format) },
                        join.format,
                    )
                } else {
                    val members = deps.parties.membersOf(partyId)
                    if (members.size != join.format.teamSize) {
                        sendSerialized<QueueServerMessage>(
                            QueueServerMessage.QueueError(
                                "${join.format.displayName} needs exactly ${join.format.teamSize} players"
                            )
                        )
                        return@webSocket
                    }
                    val rated = onStore {
                        members.map { it to deps.matchService.ratingFor(it, join.format) }
                    }
                    // Freeze the roster first: a member accepted into the party
                    // between these two calls would be in the party but not on
                    // the ticket.
                    if (!deps.parties.setQueued(partyId, true)) {
                        sendSerialized<QueueServerMessage>(
                            QueueServerMessage.QueueError("the party cannot start right now")
                        )
                        return@webSocket
                    }
                    deps.queueService.joinAsParty(partyId, rated, join.format).also {
                        if (!it) deps.parties.setQueued(partyId, false)
                    }
                }
                if (!ownsQueueEntry && !observing) {
                    sendSerialized<QueueServerMessage>(
                        QueueServerMessage.QueueError("already queued in another session")
                    )
                    return@webSocket
                }
                deps.presence.set(playerUuid, PresenceState.QUEUE)
                queuedParty = partyId

                // Read the rest of the client's messages concurrently: the
                // push loop below never yields to receive, so leave_queue would
                // otherwise sit unread until the socket closed.
                val reader = launch {
                    try {
                        while (true) {
                            val message = try {
                                receiveDeserialized<QueueClientMessage>()
                            } catch (e: Exception) {
                                if (!isUndecodableFrame(e)) throw e
                                // Skip the frame, not the loop. Dropping out of
                                // here left the only reader of leave_queue dead
                                // while the push loop happily kept the socket
                                // open: the client's Cancel was never seen, and
                                // it sat on a spinner it could no longer leave.
                                call.application.log.debug("queue socket: undecodable frame ignored", e)
                                continue
                            }
                            if (message is QueueClientMessage.LeaveQueue) {
                                leaveRequested.set(true)
                                return@launch
                            }
                        }
                    } catch (_: ClosedReceiveChannelException) {
                        // socket closed; the push loop notices via its own send
                    } catch (e: Exception) {
                        // the socket itself is gone; the push loop and the
                        // finally block are what clean up after it
                        call.application.log.debug("queue socket reader ended", e)
                    }
                }

                // push queue state until matched or the client disconnects/leaves
                var missedSnapshots = 0
                while (matched.get() == null && !leaveRequested.get() && cancelled.get() == null) {
                    val snapshot = deps.queueService.snapshot(playerUuid)
                    if (snapshot != null) {
                        missedSnapshots = 0
                        sendSerialized<QueueServerMessage>(
                            QueueServerMessage.QueueState(
                                position = snapshot.position,
                                playersInQueue = snapshot.playersInQueue,
                                waitedSeconds = snapshot.waitedSeconds,
                                etaSeconds = snapshot.etaSeconds,
                            )
                        )
                    } else if (matched.get() == null) {
                        // not in queue and not matched -> left elsewhere; end
                        // session. A party member's socket may legitimately open
                        // a beat before the leader's join lands, so give those a
                        // short grace rather than hanging up on the race.
                        missedSnapshots++
                        if (!observing || missedSnapshots > OBSERVER_GRACE_TICKS) {
                            ownsQueueEntry = false
                            break
                        }
                    }
                    delay(1.seconds)
                }
                reader.cancel()

                cancelled.get()?.let { reason ->
                    sendSerialized<QueueServerMessage>(QueueServerMessage.QueueCancelled(reason))
                    ownsQueueEntry = false
                    return@webSocket
                }
                if (leaveRequested.get()) {
                    // A leader cancelling takes the whole party's ticket with
                    // them; a member cancelling only leaves their own.
                    if (ownsQueueEntry && queuedParty != null) {
                        deps.queueService.leaveParty(queuedParty)
                        deps.parties.setQueued(queuedParty, false)
                    } else {
                        deps.queueService.leave(playerUuid)
                    }
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

                    // Same payload the party path sends; built in one place
                    // so the two cannot describe the same match differently.
                    sendSerialized<QueueServerMessage>(
                        onStore { buildMatchFound(deps, playerUuid, ready) }
                    )
                }
            } catch (_: ClosedReceiveChannelException) {
                // client disconnected
            } finally {
                deps.queueService.removeListener(listener)
                queueCancelSinks.remove(playerUuid, cancelSink)
                // NonCancellable is load-bearing, not defensive. A socket that
                // drops cancels this coroutine, and every call below suspends on
                // the queue mutex — which in a cancelled coroutine throws
                // instead of running. The player's entry then leaks: they stay
                // queued with no socket, every reconnect is refused as "already
                // queued in another session", and the client retries forever.
                withContext(NonCancellable) {
                    // matched players were already dequeued by the tick that paired
                    // them; leaving here would only dequeue someone else's entry
                    if (ownsQueueEntry && matched.get() == null) {
                        if (queuedParty != null) {
                            deps.queueService.leaveParty(queuedParty)
                            deps.parties.setQueued(queuedParty, false)
                        } else {
                            deps.queueService.leave(playerUuid)
                        }
                    }
                    deps.presence.set(
                        playerUuid,
                        if (matched.get() != null) PresenceState.MATCH
                        else if (deps.parties.partyIdOf(playerUuid) != null) PresenceState.PARTY
                        else PresenceState.MENUS,
                    )
                }
            }
        }
    }
}
