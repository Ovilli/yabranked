package dev.yabranked.backend.api

import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.backend.store.ReplayRecord
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchReplayMeta
import dev.yabranked.proto.ReplayListResponse
import dev.yabranked.proto.ReplayQuota
import dev.yabranked.proto.ReplaySummary
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Match replays: upload, download, saving and moderator review.
 *
 * Split out of [rankedApi] for size, like [socialApi], and installed into the
 * same routing block.
 *
 * A replay is a **packet capture** — every byte the match server sent each
 * participant's client — so this file moves two different kinds of thing:
 *
 * - **The index** (`MatchReplayMeta`): the card, the timeline, and which streams
 *   exist. Small, JSON, read whole.
 * - **The streams**: tens of megabytes of packets per player, appended in chunks
 *   while the match is being played and read back in ranges afterwards. They are
 *   never in the database; see `ReplayBlobStore`.
 *
 * Two things decide who may watch a recording, and they are separate on purpose:
 *
 * - **Players may watch matches they played in.** Not "matches involving someone
 *   they know", not "any match id they can name" — a capture contains everything
 *   the recipients' clients were told, and handing one to a stranger is handing
 *   them a scouting report of a base they have never seen.
 * - **Admins may watch anything**, because the whole reason the recording exists
 *   is that a free-text report saying "he was cheating" is not evidence of
 *   anything. Filing a report pins the match's replay so it survives the
 *   retention sweep whatever the players chose.
 */
fun Route.replayApi(deps: ApiDependencies) {
    suspend fun <T> onStore(block: () -> T): T = withContext(deps.storeDispatcher) { block() }

    // Its own Json rather than the routing block's: the index is stored as text
    // and only re-encoded here, and it must decode exactly the way the rest of
    // the wire does. See the note in CLAUDE.md — change one, change all.
    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun authed(call: ApplicationCall): UUID? =
        call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")?.trim()
            ?.let(deps.tokens::resolve)

    fun isAdmin(call: ApplicationCall): Boolean {
        val expected = deps.adminToken ?: return false
        val given = call.request.headers["X-Admin-Token"] ?: return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(), given.toByteArray())
    }

    /**
     * The match this request's server token belongs to, or null.
     *
     * Every agent-facing route authenticates the same way the result report does:
     * the per-match `serverToken`, compared in constant time. The orchestrator is
     * the only component that ever sees it, so possessing it *is* being that
     * match's container.
     */
    suspend fun agentMatch(call: ApplicationCall): MatchRecord? {
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty()) return null
        val matchId = runCatching { UUID.fromString(call.parameters["id"] ?: call.request.queryParameters["matchId"]) }
            .getOrNull()
        val match = matchId?.let { onStore { deps.matches.get(it) } } ?: return null
        val ok = java.security.MessageDigest.isEqual(match.serverToken.toByteArray(), token.toByteArray())
        return match.takeIf { ok }
    }

    /** One row of the "my replays" list, from [viewer]'s point of view. */
    fun summaryOf(
        record: ReplayRecord,
        match: MatchRecord?,
        people: Map<UUID, PlayerRecord>,
        viewer: UUID,
    ): ReplaySummary {
        val opponent = match?.opponentsOf(viewer)?.firstOrNull()
        val result = when {
            match == null -> "void"
            match.outcome == null || match.outcome == MatchOutcome.VOID -> "void"
            match.outcome == MatchOutcome.DRAW -> "draw"
            match.didWin(viewer) -> "win"
            else -> "loss"
        }
        return ReplaySummary(
            matchId = record.matchId.toString(),
            format = match?.format ?: dev.yabranked.proto.MatchFormat.LOCKOUT_1V1,
            result = result,
            opponentName = opponent?.let { people[it]?.name } ?: "?",
            recordedAt = record.recordedAt.epochSecond,
            durationSeconds = record.durationSeconds,
            saved = viewer in record.savedBy,
            // A pinned replay has no expiry to report; saying "expires in 3
            // days" about one the player saved is exactly the wrong thing to
            // tell them.
            expiresAt = record.expiresAt.epochSecond.takeIf { !record.pinned },
            underReview = record.underReview,
            sizeBytes = record.sizeBytes,
            partial = !record.complete,
        )
    }

    suspend fun quotaFor(player: UUID): ReplayQuota {
        val saved = onStore { deps.replays.savedFor(player) }
        return ReplayQuota(
            used = saved.size,
            limit = deps.replayPolicy.savedPerPlayer,
            usedBytes = saved.sumOf { it.sizeBytes },
            limitBytes = deps.replayPolicy.savedBytesPerPlayer,
            retentionDays = deps.replayPolicy.retentionDays.toInt(),
        )
    }

    /** The row for a match, created if the recording is only just starting. */
    suspend fun ensureRow(match: MatchRecord) {
        val now = Instant.now()
        onStore {
            deps.replays.ensure(
                matchId = match.id,
                recordedAt = now,
                expiresAt = now.plus(Duration.ofDays(deps.replayPolicy.retentionDays)),
                // A match already reported before its replay landed still gets the
                // hold: the report is what matters, not the order the two arrived in.
                underReview = deps.reports.forMatch(match.id).isNotEmpty(),
            )
        }
    }

    /*
     * Agent upload, part one: the packets.
     *
     * Chunks are offered at an offset and the answer is always the stream's
     * length, whether or not this chunk was the one that extended it. That is
     * what makes the upload survivable: a container whose request timed out
     * cannot know whether it was applied, and with this it does not need to — it
     * re-offers and is told where it is. A duplicate is therefore free rather
     * than corrupting.
     *
     * This runs *during* the match, dozens of times, which is the whole point.
     * The old design uploaded one document at the end, and the end is exactly
     * when a container is being torn down.
     */
    post("/v1/internal/matches/{id}/replay/streams/{index}") {
        val match = agentMatch(call)
        if (match == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad token"))
            return@post
        }
        val index = call.parameters["index"]?.toIntOrNull()
        val offset = call.request.queryParameters["offset"]?.toLongOrNull()
        if (index == null || index < 0 || offset == null || offset < 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed stream or offset"))
            return@post
        }
        // Read with a hard ceiling rather than trusting Content-Length: the limit
        // has to bound what the backend actually holds in memory, and a declared
        // length is a claim by the sender.
        val limit = deps.replayPolicy.maxChunkBytes
        val bytes = call.receiveStream().use { it.readNBytes(limit + 1) }
        if (bytes.size > limit) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "chunk larger than $limit bytes"))
            return@post
        }
        val held = onStore { deps.replayBlobs.totalBytes(match.id) }
        if (held + bytes.size > deps.replayPolicy.maxRecordingBytes) {
            // Answered as a length so the agent treats it as a resync and stops,
            // rather than retrying a chunk that will never be accepted.
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("length" to held))
            return@post
        }
        ensureRow(match)
        // Read the length before appending rather than inferring from the length
        // after: a duplicate chunk offered at 0 when the stream is already exactly
        // that long lands on `offset + size` too, so the *after* value cannot tell
        // "applied" from "ignored" — which is the one distinction this route owes
        // its caller.
        val before = onStore { deps.replayBlobs.length(match.id, index) }
        val length = onStore { deps.replayBlobs.append(match.id, index, offset, bytes) }
        onStore { deps.replays.setSizeBytes(match.id, deps.replayBlobs.totalBytes(match.id)) }
        // 409 means "you were at the wrong offset, here is the right one" — a
        // resync, not a failure, and the agent reads both the same way.
        val status = if (before == offset) HttpStatusCode.OK else HttpStatusCode.Conflict
        call.respond(status, mapOf("length" to length))
    }

    /*
     * Agent upload, part two: the index.
     *
     * Sent at every checkpoint and once more when the recording is complete.
     * `complete=false` publishes a partial recording, which is playable up to
     * where the upload got to — that is what makes a container killed mid-match
     * still leave something watchable.
     *
     * Taken as raw text and stored as raw text: the backend never needs the
     * decoded form. It is still *parsed* once, so a malformed body is refused here
     * rather than becoming a replay that only fails when a player opens it.
     */
    post("/v1/internal/matches/{id}/replay") {
        val match = agentMatch(call)
        if (match == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "bad token"))
            return@post
        }
        val body = call.receiveText()
        if (body.length > deps.replayPolicy.maxMetaBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "replay index too large"))
            return@post
        }
        val meta = runCatching { json.decodeFromString(MatchReplayMeta.serializer(), body) }.getOrNull()
        if (meta == null || runCatching { UUID.fromString(meta.matchId) }.getOrNull() != match.id) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed replay index"))
            return@post
        }
        val complete = call.request.queryParameters["complete"]?.toBooleanStrictOrNull() ?: true
        ensureRow(match)
        onStore { deps.replays.putMeta(match.id, body, meta.durationSeconds, complete) }
        call.respond(HttpStatusCode.OK, mapOf("status" to "stored"))
    }

    /**
     * Who may read this match's recording: a participant, or an admin. Null when
     * the answer is "you may not", which is deliberately indistinguishable from
     * "there is no such match".
     */
    suspend fun readable(call: ApplicationCall): MatchRecord? {
        val viewer = authed(call)
        val admin = isAdmin(call)
        if (viewer == null && !admin) return null
        val matchId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull() ?: return null
        val match = onStore { deps.matches.get(matchId) } ?: return null
        return match.takeIf { admin || it.sideOf(viewer!!) != null }
    }

    /*
     * The recording's index. Participants and admins only — see the file header.
     */
    get("/v1/matches/{id}/replay") {
        val match = readable(call)
        if (match == null) {
            // "Not yours" and "does not exist" answer the same way: which match
            // ids exist is not something this endpoint owes a stranger.
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no replay for this match"))
            return@get
        }
        val record = onStore { deps.replays.get(match.id) }
        if (record == null || !record.playable) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no replay for this match"))
            return@get
        }
        // Straight back out as the text it was stored as.
        call.respondText(record.meta, ContentType.Application.Json)
    }

    /*
     * One range of one stream.
     *
     * A recording is downloaded to a local cache before it is played, in ranges
     * rather than as one response, because the client shows progress against it
     * and because a match interrupted halfway through a download must be able to
     * resume rather than start again. `X-Replay-Stream-Length` is the whole
     * stream's size, which is how the client knows when it has all of it — and it
     * can grow between requests while a match is still being recorded.
     */
    get("/v1/matches/{id}/replay/streams/{index}") {
        val match = readable(call)
        if (match == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no replay for this match"))
            return@get
        }
        val index = call.parameters["index"]?.toIntOrNull()
        if (index == null || index < 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed stream index"))
            return@get
        }
        val record = onStore { deps.replays.get(match.id) }
        if (record == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no replay for this match"))
            return@get
        }
        val offset = call.request.queryParameters["offset"]?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val length = call.request.queryParameters["length"]?.toIntOrNull()
            ?.coerceIn(1, MAX_DOWNLOAD_CHUNK) ?: MAX_DOWNLOAD_CHUNK
        val total = onStore { deps.replayBlobs.length(match.id, index) }
        val bytes = onStore { deps.replayBlobs.read(match.id, index, offset, length) }
        call.response.headers.append("X-Replay-Stream-Length", total.toString())
        call.respondBytes(bytes, ContentType.Application.OctetStream)
    }

    /** The replays this player has kept, and how much more they may keep. */
    get("/v1/players/me/replays") {
        val player = authed(call)
        if (player == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@get
        }
        val rows = onStore { deps.replays.savedFor(player) }
        val matches = onStore { rows.mapNotNull { deps.matches.get(it.matchId) } }.associateBy { it.id }
        val people = onStore {
            deps.players.getPlayers(matches.values.flatMap { it.participants })
        }
        call.respond(
            ReplayListResponse(
                replays = rows.map { summaryOf(it, matches[it.matchId], people, player) },
                quota = quotaFor(player),
            )
        )
    }

    /*
     * Keep this replay.
     *
     * Every match makes a recording and they are large, so the default is that
     * they expire; saving is the player saying "not this one". The cap is per
     * player rather than global — one player filling the disk must not cost
     * everyone else their replays — and it is counted in bytes as well as files,
     * because ten packet captures are a hundred times the ten sample tracks the
     * file count was chosen for.
     */
    post("/v1/matches/{id}/replay/save") {
        val player = authed(call)
        if (player == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@post
        }
        val matchId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        val match = matchId?.let { onStore { deps.matches.get(it) } }
        if (match == null || match.sideOf(player) == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no such match for this player"))
            return@post
        }
        val record = onStore { deps.replays.get(match.id) }
        if (record == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "no replay was recorded for that match"))
            return@post
        }
        // Checked before the write and not inside the store: only this layer
        // knows the limit, and the store has no business enforcing policy.
        val quota = quotaFor(player)
        if (!quota.fits(record.sizeBytes) && player !in record.savedBy) {
            val reason =
                if (quota.used >= quota.limit) "you already have ${quota.limit} saved replays — delete one first"
                else "your saved replays would go over ${quota.limitBytes / (1024 * 1024)} MB — delete one first"
            call.respond(HttpStatusCode.Conflict, mapOf("error" to reason))
            return@post
        }
        onStore { deps.replays.save(match.id, player) }
        call.respond(HttpStatusCode.OK, mapOf("status" to "saved"))
    }

    /**
     * Stop keeping it. Only drops *this* player's pin — a co-player's copy and
     * a moderator hold both survive, which is the point of tracking them apart.
     */
    delete("/v1/matches/{id}/replay/save") {
        val player = authed(call)
        if (player == null) {
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "login required"))
            return@delete
        }
        val matchId = runCatching { UUID.fromString(call.parameters["id"]) }.getOrNull()
        if (matchId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "malformed match id"))
            return@delete
        }
        onStore { deps.replays.unsave(matchId, player) }
        call.respond(HttpStatusCode.OK, mapOf("status" to "removed"))
    }

    /** Every replay held for review, i.e. every reported match that has one. */
    get("/v1/admin/replays") {
        if (!isAdmin(call)) {
            call.respond(HttpStatusCode.Forbidden, mapOf("error" to "admin token required"))
            return@get
        }
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50
        val rows = onStore { deps.replays.underReview(limit) }
        val matches = onStore { rows.mapNotNull { deps.matches.get(it.matchId) } }.associateBy { it.id }
        call.respond(
            rows.map { record ->
                val match = matches[record.matchId]
                val reports = onStore { deps.reports.forMatch(record.matchId) }
                mapOf(
                    "matchId" to record.matchId.toString(),
                    "format" to (match?.format?.wire ?: "unknown"),
                    "recordedAt" to record.recordedAt.toString(),
                    "durationSeconds" to record.durationSeconds.toString(),
                    "sizeBytes" to record.sizeBytes.toString(),
                    "complete" to record.complete.toString(),
                    "reports" to reports.size.toString(),
                    "accused" to reports.joinToString(",") { it.accused.toString() },
                )
            }
        )
    }
}

/**
 * Largest range one download request will answer with. Bounds what the backend
 * buffers per request; the client asks repeatedly rather than for a whole stream,
 * so it is a page size, not a limit on recording length.
 */
private const val MAX_DOWNLOAD_CHUNK = 4 * 1024 * 1024
