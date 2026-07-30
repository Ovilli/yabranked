package dev.yabranked.backend.api

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.rating.Tier
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.social.EndorsementService
import dev.yabranked.backend.social.FriendService
import dev.yabranked.backend.social.PartyService
import dev.yabranked.backend.store.FriendRequestRecord
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.proto.EndorsementPrompt
import dev.yabranked.proto.EndorsementRequest
import dev.yabranked.proto.Friend
import dev.yabranked.proto.FriendListResponse
import dev.yabranked.proto.FriendRequestCreate
import dev.yabranked.proto.FriendRequestView
import dev.yabranked.proto.LeaderboardCategory
import dev.yabranked.proto.LeaderboardResponse
import dev.yabranked.proto.LeaderboardRow
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.PartyClientMessage
import dev.yabranked.proto.PartyServerMessage
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.PresenceState
import dev.yabranked.proto.RecentPlayer
import dev.yabranked.proto.Visibility
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.log
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.websocket.receiveDeserialized
import io.ktor.server.websocket.sendSerialized
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Friends, parties, endorsements and the per-mode leaderboards.
 *
 * Split out of [rankedApi] for size only; it is installed into the same
 * routing block and shares [ApiDependencies], so there is exactly one
 * [PartyService] and one [FriendService] behind both files.
 *
 * Every rule about who may do what lives in the services, not here — this layer
 * only authenticates the caller, parses ids, and turns service results into
 * status codes.
 */
fun Route.socialApi(deps: ApiDependencies) {
    // The scope party-start background work runs in: it has to outlive the
    // socket that asked for it, since the wait for a match server is longer
    // than a client will sit still.
    val partyScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )
    val dispatcher: CoroutineDispatcher = deps.storeDispatcher
    suspend fun <T> onStore(block: () -> T): T = withContext(dispatcher) { block() }

    fun authed(call: ApplicationCall): UUID? =
        call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim()
            ?.let(deps.tokens::resolve)

    fun refOf(record: PlayerRecord?, uuid: UUID): PlayerRef = PlayerRef(
        uuid = uuid.toString(),
        name = record?.name ?: "?",
        // A country is public or it is not; the friends list is not a reason to
        // leak one the player hid from everyone else.
        country = record?.country.takeIf { record?.privacy?.showCountry == Visibility.EVERYONE },
    )

    /** Presence as [viewer] may see it — hidden status reads as offline. */
    fun presenceFor(viewer: UUID, subject: PlayerRecord?, uuid: UUID): PresenceState {
        subject ?: return PresenceState.OFFLINE
        val allowed = subject.privacy.showOnlineStatus.allows(
            isSelf = viewer == uuid,
            isFriend = deps.friends.areFriends(viewer, uuid),
        )
        return if (allowed) deps.presence.stateOf(uuid) else PresenceState.OFFLINE
    }

    fun viewOf(request: FriendRequestRecord, self: UUID, people: Map<UUID, PlayerRecord>) =
        FriendRequestView(
            id = request.id.toString(),
            from = refOf(people[request.from], request.from),
            to = refOf(people[request.to], request.to),
            createdAt = request.createdAt.toEpochMilli(),
            outgoing = request.from == self,
        )

    // --- Friends ---

    get("/v1/friends") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@get
        }
        val response = onStore {
            val friendIds = deps.friends.friendsOf(self)
            val incoming = deps.friends.incoming(self)
            val outgoing = deps.friends.outgoing(self)
            // one lookup for every name on the screen, rather than one per row
            val people = deps.players.getPlayers(
                friendIds + incoming.map { it.from } + outgoing.map { it.to } + self
            )
            val since = deps.friendStore.friendsOf(self).associate { it.other(self) to it.since }
            val partyId = deps.parties.partyIdOf(self)
            val partyMembers = partyId?.let(deps.parties::membersOf).orEmpty().toSet()

            FriendListResponse(
                friends = friendIds.map { uuid ->
                    val record = people[uuid]
                    val stats = deps.matchService.statsFor(uuid)
                    val showRating = record?.privacy?.showRating
                        ?.allows(isSelf = false, isFriend = true) ?: false
                    Friend(
                        player = refOf(record, uuid),
                        presence = presenceFor(self, record, uuid),
                        since = since[uuid]?.toEpochMilli() ?: 0,
                        rating = stats.rating.takeIf { showRating },
                        tier = Tier.format(
                            stats.rating,
                            isPlaced = deps.matchService.placementMatchesRemaining(stats) <= 0,
                        ),
                        inYourParty = uuid in partyMembers,
                        invitable = record?.privacy?.allowPartyInvites ?: false,
                    )
                }.sortedWith(
                    // online friends first, then alphabetically: the list exists
                    // to answer "who can I play with right now"
                    compareBy({ it.presence == PresenceState.OFFLINE }, { it.player.name.lowercase() })
                ),
                incoming = incoming.map { viewOf(it, self, people) },
                outgoing = outgoing.map { viewOf(it, self, people) },
                maxFriends = deps.friends.maxFriends,
            )
        }
        call.respond(response)
    }

    /**
     * People the caller has recently played with — a shortcut for the common
     * case, not a restriction: anyone who has used the mod can be added by name.
     * Each row carries whether it is requestable so the client greys the button
     * rather than discovering the refusal on submit.
     */
    get("/v1/friends/recent") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@get
        }
        val recent = onStore {
            val played = deps.friends.recentPlayers(self)
            val people = deps.players.getPlayers(played.map { it.first })
            played.mapNotNull { (uuid, at) ->
                val record = people[uuid] ?: return@mapNotNull null
                if (record.isBanned) return@mapNotNull null
                RecentPlayer(
                    player = refOf(record, uuid),
                    lastPlayedAt = at.toEpochMilli(),
                    matchesTogether = deps.friends.matchesTogether(self, uuid),
                    alreadyFriends = deps.friends.areFriends(self, uuid),
                    requestPending = deps.friends.pendingBetween(self, uuid) != null,
                    requestable = deps.friends.canRequest(self, uuid) == null,
                )
            }
        }
        call.respond(recent)
    }

    post("/v1/friends/requests") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@post
        }
        // Friend requests are a notification others receive, so they get their
        // own budget: without one, one account can spam every player it meets.
        if (throttle(call, deps, deps.rateLimits.friendRequests, self.toString())) return@post

        val body = call.receive<FriendRequestCreate>()
        // A name resolves against every account that has used the mod, not just
        // the people the caller has played with — you usually want to add a
        // friend *before* the first game, not after it.
        val target = onStore {
            body.uuid?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: body.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { deps.players.findByName(it)?.uuid }
        }
        if (target == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown player"))
            return@post
        }

        when (val result = onStore { deps.friends.request(self, target) }) {
            is FriendService.RequestResult.Rejected ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to result.reason))

            FriendService.RequestResult.AutoAccepted ->
                call.respond(HttpStatusCode.OK, mapOf("status" to "accepted"))

            is FriendService.RequestResult.Sent -> {
                // Deliver it live if they are online, so a friend request during
                // a session does not wait for the next friends-list refresh.
                val people = onStore { deps.players.getPlayers(listOf(self, target)) }
                deps.parties.notify(
                    target,
                    PartyServerMessage.FriendRequested(viewOf(result.request, target, people)),
                )
                call.respond(HttpStatusCode.OK, mapOf("status" to "sent"))
            }
        }
    }

    post("/v1/friends/requests/{id}/accept") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@post
        }
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        val accepted = id?.let { onStore { deps.friends.accept(self, it) } }
        if (accepted == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such request"))
            return@post
        }
        call.respond(HttpStatusCode.OK, mapOf("status" to "accepted", "friend" to accepted.toString()))
    }

    /** Decline as the addressee, or cancel as the sender — the same delete. */
    delete("/v1/friends/requests/{id}") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@delete
        }
        val id = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        val removed = id != null && onStore { deps.friends.dismiss(self, id) }
        if (!removed) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such request"))
            return@delete
        }
        call.respond(HttpStatusCode.OK, mapOf("status" to "dismissed"))
    }

    delete("/v1/friends/{uuid}") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@delete
        }
        val other = runCatching { UUID.fromString(call.parameters["uuid"]) }.getOrNull()
        val removed = other != null && onStore { deps.friends.remove(self, other) }
        if (!removed) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "not friends"))
            return@delete
        }
        call.respond(HttpStatusCode.OK, mapOf("status" to "removed"))
    }

    // --- Endorsements ---

    /** Who the caller may still endorse for a match, or 404 once the window closed. */
    get("/v1/matches/{id}/endorsements") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@get
        }
        val matchId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        val prompt = matchId?.let {
            onStore {
                val teammates = deps.endorsements.promptFor(self, it) ?: return@onStore null
                val people = deps.players.getPlayers(teammates)
                val match = deps.matches.get(it)
                EndorsementPrompt(
                    matchId = it.toString(),
                    teammates = teammates.map { mate -> refOf(people[mate], mate) },
                    expiresAt = match?.completedAt
                        ?.plus(deps.endorsements.window)?.toEpochMilli() ?: 0,
                    alreadyEndorsed = false,
                )
            }
        }
        if (prompt == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "nothing to endorse for that match"))
            return@get
        }
        call.respond(prompt)
    }

    post("/v1/endorsements") {
        val self = authed(call) ?: run {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@post
        }
        val body = call.receive<EndorsementRequest>()
        val matchId = runCatching { UUID.fromString(body.matchId) }.getOrNull()
        if (matchId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed match id"))
            return@post
        }
        val targets = body.endorsed.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
        when (val result = onStore { deps.endorsements.endorse(self, matchId, targets, body.category) }) {
            is EndorsementService.Result.Ok ->
                call.respond(HttpStatusCode.OK, mapOf("endorsed" to result.awarded.toString()))

            is EndorsementService.Result.Rejected ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to result.reason))
        }
    }

    // --- Leaderboards ---

    get("/v1/leaderboards") {
        call.respond(leaderboardCategories())
    }

    get("/v1/leaderboards/{id}") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 25
        val season = call.request.queryParameters["season"]?.toIntOrNull() ?: deps.seasons.currentSeason
        val category = leaderboardCategories().firstOrNull { it.id == call.parameters["id"] }
        if (category == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown leaderboard"))
            return@get
        }
        call.respond(onStore { buildLeaderboard(deps, category, season, limit) })
    }

    // --- Party socket ---

    /**
     * The party's control channel, and the only way party state reaches a
     * client. One socket per account: [PartyService.connect] replaces an older
     * one rather than letting two sockets act as the same player.
     */
    webSocket("/v1/party") {
        val token = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }
            ?: call.request.queryParameters["token"]?.takeIf { deps.allowQueryToken }
        val self = token?.let(deps.tokens::resolve)
        if (self == null) {
            sendSerialized<PartyServerMessage>(PartyServerMessage.Error("unauthorized"))
            close()
            return@webSocket
        }
        val record = onStore { deps.players.getPlayer(self) }
        if (record == null || record.isBanned) {
            sendSerialized<PartyServerMessage>(PartyServerMessage.Error("unknown player"))
            close()
            return@webSocket
        }

        // The service pushes from whatever thread performed the mutation, which
        // is not this socket's coroutine — hand the frame over rather than
        // sending from there.
        val outbox = kotlinx.coroutines.channels.Channel<PartyServerMessage>(capacity = 64)
        val sink: (PartyServerMessage) -> Unit = { message -> outbox.trySend(message) }

        try {
            deps.parties.connect(self, sink)

            launch { for (message in outbox) sendSerialized(message) }

            while (true) {
                val message = try {
                    receiveDeserialized<PartyClientMessage>()
                } catch (e: Exception) {
                    if (!isUndecodableFrame(e)) throw e
                    // One frame this build cannot decode used to end the whole
                    // socket — and ending the socket is what drops the player
                    // out of their party. Refuse the frame instead, and tell
                    // them why rather than leaving the screen to freeze.
                    call.application.log.debug("party socket: undecodable frame refused", e)
                    sink(PartyServerMessage.Error("unsupported message"))
                    continue
                }
                // Every mutation touches the stores through the party lookup, so
                // none of them may run on the event loop.
                val result: PartyService.Result? = onStore {
                    when (message) {
                        PartyClientMessage.Hello -> {
                            deps.presence.touch(self)
                            PartyService.Result.Ok(deps.parties.viewFor(self))
                        }

                        PartyClientMessage.Ping -> {
                            deps.presence.touch(self)
                            null
                        }

                        PartyClientMessage.Create -> deps.parties.create(self)
                        PartyClientMessage.Leave -> deps.parties.leave(self)

                        is PartyClientMessage.Invite ->
                            withUuid(message.uuid) { deps.parties.invite(self, it) }

                        is PartyClientMessage.AcceptInvite ->
                            withUuid(message.partyId) { deps.parties.acceptInvite(self, it) }

                        is PartyClientMessage.DeclineInvite ->
                            withUuid(message.partyId) { deps.parties.declineInvite(self, it) }

                        is PartyClientMessage.Kick ->
                            withUuid(message.uuid) { deps.parties.kick(self, it) }

                        is PartyClientMessage.Promote ->
                            withUuid(message.uuid) { deps.parties.promote(self, it) }

                        is PartyClientMessage.SetOptions -> deps.parties.setOptions(self, message.options)

                        is PartyClientMessage.SetTeam ->
                            withUuid(message.uuid) { deps.parties.setTeam(self, it, message.team) }

                        is PartyClientMessage.SetReady -> deps.parties.setReady(self, message.ready)

                        // Handled outside this block: starting a match has to
                        // wait for a server to come up, which must not hold the
                        // store dispatcher.
                        PartyClientMessage.StartMatch -> null
                    }
                }
                if (message is PartyClientMessage.StartMatch) {
                    startPartyMatch(deps, partyScope, self)?.let {
                        sink(PartyServerMessage.StartFailed(it))
                    }
                }
                // Successful mutations are already broadcast as full state by
                // the service; only the refusal needs answering here.
                if (result is PartyService.Result.Rejected) {
                    sink(PartyServerMessage.Error(result.reason))
                } else if (message is PartyClientMessage.Hello && result is PartyService.Result.Ok) {
                    sink(PartyServerMessage.State(result.party))
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // client disconnected
        } catch (e: Exception) {
            // A dead socket is the ordinary way out of this loop; anything else
            // is a bug nobody would ever hear about otherwise, since all the
            // client sees is its party screen going quiet. The finally block
            // still cleans up either way.
            call.application.log.warn("party socket for {} ended abnormally", self, e)
        } finally {
            outbox.close()
            deps.parties.disconnect(self, sink)
        }
    }
}

/**
 * Start the party's own match, or answer with why it cannot.
 *
 * Only for party-only formats: those have no opponent to find, so there is
 * nothing for the matchmaker to do and the party simply plays itself. Open-queue
 * formats still go through the queue socket, which is what finds them an
 * opponent.
 *
 * Returns null once the match is created and the wait for its server has been
 * handed to a background job; the members hear about it as
 * [PartyServerMessage.MatchStarting].
 */
private suspend fun startPartyMatch(
    deps: ApiDependencies,
    scope: kotlinx.coroutines.CoroutineScope,
    self: UUID,
): String? {
    val dispatcher = deps.storeDispatcher
    suspend fun <T> onStore(block: () -> T): T = withContext(dispatcher) { block() }

    val partyId = deps.parties.partyIdOf(self) ?: return "you are not in a party"
    if (!deps.parties.isLeader(self)) return "only the party leader can start the match"
    val view = deps.parties.viewOf(partyId) ?: return "that party no longer exists"
    val options = view.options
    if (!options.format.partyOnly) {
        return "${options.format.displayName} is found through the queue, not started here"
    }
    view.startBlockedReason?.let { return it }

    // Freeze the roster *before* building the match: a member accepted between
    // resolving the teams and creating the record would be in the party but not
    // in the match.
    if (!deps.parties.setQueued(partyId, true)) return "the party cannot start right now"

    val teams = deps.parties.resolveTeams(partyId)
    if (teams == null) {
        deps.parties.setQueued(partyId, false)
        return "the party cannot start right now"
    }

    val record = try {
        onStore {
            deps.matchService.createTeamMatch(
                MatchService.TeamMatchRequest(
                    format = options.format,
                    teams = teams,
                    sharedWorld = options.sharedWorld,
                    sharedSeed = options.sharedSeed,
                    ranked = options.ranked,
                    partyId = partyId,
                )
            )
        }
    } catch (e: Exception) {
        deps.parties.setQueued(partyId, false)
        return e.message ?: "could not create the match"
    }

    // The server takes up to a couple of minutes to boot, so the wait cannot
    // block this socket's message loop — everyone would stop hearing party
    // updates until it finished.
    scope.launch {
        val members = teams.flatten()
        val deadline = System.nanoTime() +
            MatchService.PROVISION_TIMEOUT_SECONDS * 1_000_000_000L
        var ready: dev.yabranked.backend.store.MatchRecord? = null
        while (System.nanoTime() < deadline) {
            val current = onStore { deps.matches.get(record.id) }
            if (current == null || current.status == MatchStatus.VOIDED) break
            if (current.status == MatchStatus.ACTIVE && current.serverAddress != null) {
                ready = current
                break
            }
            delay(1000)
        }
        deps.parties.setQueued(partyId, false)
        if (ready == null) {
            onStore { deps.matchService.voidMatch(record.id) }
            for (member in members) {
                deps.parties.notify(member, PartyServerMessage.StartFailed("the match server could not be started"))
            }
            return@launch
        }
        for (member in members) {
            val payload = onStore { buildMatchFound(deps, member, ready) }
            deps.parties.notify(member, PartyServerMessage.MatchStarting(payload))
        }
    }
    return null
}

/** Parses [raw] as a uuid and applies [block], or refuses cleanly. */
private inline fun withUuid(raw: String, block: (UUID) -> PartyService.Result): PartyService.Result {
    val uuid = runCatching { UUID.fromString(raw) }.getOrNull()
        ?: return PartyService.Result.Rejected("malformed id")
    return block(uuid)
}

private suspend fun throttle(
    call: ApplicationCall,
    deps: ApiDependencies,
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
 * Every selectable leaderboard.
 *
 * One rating board per rated mode — the point of the whole change, since a
 * player's 3v3 standing says nothing about their 1v1 one — a wins board per
 * casual mode, and three cross-mode boards for the kinds of contribution
 * rating never measures.
 */
internal fun leaderboardCategories(): List<LeaderboardCategory> = buildList {
    add(LeaderboardCategory(id = "overall", displayName = "Overall", metric = "rating"))
    for (format in MatchFormat.entries.filter { it.playable }) {
        add(
            LeaderboardCategory(
                id = format.wire,
                displayName = format.displayName,
                format = format,
                category = format.category,
                metric = if (format.ranked) "rating" else "wins",
            )
        )
    }
    add(LeaderboardCategory(id = "endorsements", displayName = "Most endorsed", metric = "endorsements"))
    add(LeaderboardCategory(id = "playtime", displayName = "Most time played", metric = "playtime"))
    add(LeaderboardCategory(id = "streak", displayName = "Longest win streak", metric = "streak"))
}

private fun formatDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** Assembles one board. Blocking; callers dispatch it off the event loop. */
private fun buildLeaderboard(
    deps: ApiDependencies,
    category: LeaderboardCategory,
    season: Int,
    limit: Int,
): LeaderboardResponse {
    val minMatches = deps.matchService.placementMatches

    /** (uuid, sort key, display string, the mode row it came from). */
    data class Entry(
        val uuid: UUID,
        val value: Long,
        val display: String,
        val tier: String? = null,
        val matchesPlayed: Int = 0,
        val wins: Int = 0,
        val losses: Int = 0,
        val streak: Int = 0,
    )

    val format = category.format
    val entries: List<Entry> = when {
        category.id == "overall" ->
            deps.players.leaderboard(season, limit, minMatches).map { (stats, _) ->
                Entry(
                    uuid = stats.uuid,
                    value = stats.rating.toLong(),
                    display = stats.rating.toString(),
                    tier = Tier.format(stats.rating, isPlaced = true),
                    matchesPlayed = stats.matchesPlayed,
                    wins = stats.wins,
                    losses = stats.losses,
                )
            }

        category.id == "endorsements" ->
            deps.endorsementStore.top(limit).map { (uuid, total) ->
                Entry(uuid = uuid, value = total.toLong(), display = total.toString())
            }

        category.id == "playtime" ->
            deps.matchService.modeLadder.topPlaytime(season, limit).map { (uuid, seconds) ->
                Entry(uuid = uuid, value = seconds, display = formatDuration(seconds))
            }

        category.id == "streak" ->
            deps.matchService.modeLadder.topStreak(season, limit).map { (uuid, streak) ->
                Entry(uuid = uuid, value = streak.toLong(), display = streak.toString(), streak = streak)
            }

        format != null && category.metric == "rating" ->
            deps.matchService.modeLadder
                .top(season, format, limit, deps.matchService.teamPlacementMatches)
                .map { row ->
                    Entry(
                        uuid = row.uuid,
                        value = row.rating.toLong(),
                        display = row.rating.toString(),
                        tier = Tier.format(row.rating, isPlaced = true),
                        matchesPlayed = row.matchesPlayed,
                        wins = row.wins,
                        losses = row.losses,
                        streak = row.currentStreak,
                    )
                }

        format != null ->
            // An unrated mode has no ladder, so it is ranked by wins — which is
            // the whole reason casual modes get a board at all.
            deps.matchService.modeLadder.top(season, format, limit * 4, minMatches = 1)
                .sortedByDescending { it.wins }
                .take(limit)
                .map { row ->
                    Entry(
                        uuid = row.uuid,
                        value = row.wins.toLong(),
                        display = row.wins.toString(),
                        matchesPlayed = row.matchesPlayed,
                        wins = row.wins,
                        losses = row.losses,
                        streak = row.currentStreak,
                    )
                }

        else -> emptyList()
    }

    val people = deps.players.getPlayers(entries.map { it.uuid })
    val endorsements = deps.endorsements.summariesFor(entries.map { it.uuid })

    return LeaderboardResponse(
        category = category,
        season = season,
        rows = entries.mapIndexed { index, entry ->
            val record = people[entry.uuid]
            LeaderboardRow(
                rank = index + 1,
                player = PlayerRef(
                    entry.uuid.toString(),
                    record?.name ?: "?",
                    // A leaderboard is public by definition, so a hidden country
                    // stays hidden here too.
                    country = record?.country
                        .takeIf { record?.privacy?.showCountry == Visibility.EVERYONE },
                ),
                value = entry.display,
                numericValue = entry.value,
                tier = entry.tier,
                background = record?.background ?: "default",
                endorsementLevel = endorsements[entry.uuid]?.level ?: 1,
                currentStreak = entry.streak,
                matchesPlayed = entry.matchesPlayed,
                wins = entry.wins,
                losses = entry.losses,
            )
        },
    )
}
