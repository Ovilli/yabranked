package dev.yabranked.backend.api

import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueEntry
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.InMemoryReplayBlobStore
import dev.yabranked.backend.store.InMemoryReplayStore
import dev.yabranked.backend.store.InMemoryReportStore
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.ReplayPolicy
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchReplayMeta
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.ReplayBoard
import dev.yabranked.proto.ReplayCell
import dev.yabranked.proto.ReplayEvent
import dev.yabranked.proto.ReplayEventType
import dev.yabranked.proto.ReplayListResponse
import dev.yabranked.proto.ReplayStreamInfo
import dev.yabranked.proto.ReportRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The replay endpoints.
 *
 * Three things matter here and they are all load-bearing: a recording is only
 * readable by someone who was in the match (or an admin), filing a report is what
 * keeps it from being swept, and a chunk upload is offset-addressed so a container
 * that cannot tell whether its last request landed can simply ask again.
 */
class ReplayApiTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val replays = InMemoryReplayStore()
    private val blobs = InMemoryReplayBlobStore()
    private val reports = InMemoryReportStore()
    private val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
    private val queueService = QueueService(MatchmakingQueue(), matchService)

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private val anna = UUID.randomUUID()
    private val ben = UUID.randomUUID()
    private val stranger = UUID.randomUUID()

    private fun deps(policy: ReplayPolicy = ReplayPolicy()) = ApiDependencies(
        verifier = FakeSessionVerifier(),
        players = players,
        matches = matches,
        matchService = matchService,
        queueService = queueService,
        reports = reports,
        replays = replays,
        replayBlobs = blobs,
        replayPolicy = policy,
        adminToken = ADMIN_TOKEN,
    )

    private fun aMatch(): MatchRecord {
        matchService.getOrCreatePlayer(anna, "Anna")
        matchService.getOrCreatePlayer(ben, "Ben")
        matchService.getOrCreatePlayer(stranger, "Stranger")
        return matchService.createMatch(
            QueueMatch(
                QueueEntry(anna, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
                QueueEntry(ben, 1000, MatchFormat.LOCKOUT_1V1, Instant.now()),
            ),
            MatchFormat.LOCKOUT_1V1,
        )
    }

    /** Stand-in for a captured stream. The routes never look inside one. */
    private val packets = ByteArray(64) { it.toByte() }

    private fun aMeta(matchId: UUID) = MatchReplayMeta(
        matchId = matchId.toString(),
        durationSeconds = 600,
        recordedFrom = 1_700_000_000_000,
        gameStartMillis = 4_000,
        board = ReplayBoard(
            cells = listOf(
                ReplayCell(
                    index = 0,
                    objectiveId = "minecraft:diamond",
                    claimedBy = PlayerRef(anna.toString(), "Anna"),
                    claimedByTeam = 0,
                    claimedAtSeconds = 42,
                )
            )
        ),
        events = listOf(ReplayEvent(42, ReplayEventType.CLAIM, cell = 0, detail = "Anna claimed diamond")),
        streams = listOf(
            ReplayStreamInfo(index = 0, player = PlayerRef(anna.toString(), "Anna"), sizeBytes = packets.size.toLong()),
        ),
        protocolVersion = 1,
    )

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }

    /** A player token for [uuid], minted the way the routes expect. */
    private fun token(uuid: UUID, deps: ApiDependencies): String = deps.tokens.issue(uuid)

    @Test
    fun `agent uploads packets and an index, and a participant reads both back`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()
        upload(client, match)

        val read = client.get("/v1/matches/${match.id}/replay") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }
        assertEquals(HttpStatusCode.OK, read.status)
        val decoded = json.decodeFromString(MatchReplayMeta.serializer(), read.bodyAsText())
        assertEquals(1, decoded.board.cells.size)
        assertEquals(42L, decoded.board.cells.first().claimedAtSeconds)
        assertEquals(4_000L, decoded.gameStartMillis)

        val stream = client.get("/v1/matches/${match.id}/replay/streams/0") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }
        assertEquals(HttpStatusCode.OK, stream.status)
        assertContentEquals(packets, stream.bodyAsBytes())
        assertEquals(
            packets.size.toString(),
            stream.headers["X-Replay-Stream-Length"],
            "the client needs the whole stream's length to know when its download is done",
        )
    }

    @Test
    fun `a chunk at the wrong offset is answered with the real length instead of being applied`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()

        assertEquals(HttpStatusCode.OK, appendChunk(client, match, offset = 0, bytes = packets).status)

        // The agent retrying a chunk it already sent — which is what a timed-out
        // request looks like from inside a container — must not append it twice.
        val repeat = appendChunk(client, match, offset = 0, bytes = packets)
        assertEquals(HttpStatusCode.Conflict, repeat.status)
        assertTrue(repeat.bodyAsText().contains("\"length\":${packets.size}"))
        assertEquals(packets.size.toLong(), blobs.length(match.id, 0))
    }

    @Test
    fun `a partial recording is offered as partial rather than as a whole match`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()

        appendChunk(client, match, offset = 0, bytes = packets)
        putMeta(client, match, complete = false)
        client.post("/v1/matches/${match.id}/replay/save") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }

        val mine: ReplayListResponse = client.get("/v1/players/me/replays") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }.body()
        assertTrue(mine.replays.single().partial, "a checkpointed upload is not a finished match")
        assertEquals(packets.size.toLong(), mine.replays.single().sizeBytes)
        assertEquals(packets.size.toLong(), mine.quota.usedBytes)
    }

    @Test
    fun `an index arriving before the match settles is accepted`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()

        // Load-bearing: the agent uploads *before* it reports the result, because
        // settling fires the orchestrator's teardown and teardown is `docker rm -f`
        // on the container doing the uploading. If this route ever started
        // requiring a settled match, every replay would be lost to that race and
        // every "Save replay" would answer "nothing to save".
        assertEquals(dev.yabranked.backend.store.MatchStatus.PENDING, matches.get(match.id)!!.status)
        upload(client, match)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/matches/${match.id}/replay/save") {
                header("Authorization", "Bearer ${token(anna, deps)}")
            }.status,
        )
    }

    @Test
    fun `saving a match that never recorded one says so`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()

        // No orchestrator means no agent means no recording. The player gets a
        // sentence about that rather than a generic failure.
        val response = client.post("/v1/matches/${match.id}/replay/save") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("no replay was recorded"))
    }

    @Test
    fun `a wrong match token is refused for both the packets and the index`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()
        val match = aMatch()

        val chunk = client.post("/v1/internal/matches/${match.id}/replay/streams/0?offset=0") {
            header("Authorization", "Bearer not-the-token")
            setBody(packets)
        }
        assertEquals(HttpStatusCode.Unauthorized, chunk.status)

        val meta = client.post("/v1/internal/matches/${match.id}/replay") {
            header("Authorization", "Bearer not-the-token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MatchReplayMeta.serializer(), aMeta(match.id)))
        }
        assertEquals(HttpStatusCode.Unauthorized, meta.status)
    }

    @Test
    fun `someone who was not in the match cannot watch it`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()
        upload(client, match)

        // A capture is everything the recipients' clients were told; a stranger
        // holding a match id must not be able to scout with one.
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/matches/${match.id}/replay") {
                header("Authorization", "Bearer ${token(stranger, deps)}")
            }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.get("/v1/matches/${match.id}/replay/streams/0") {
                header("Authorization", "Bearer ${token(stranger, deps)}")
            }.status,
        )
    }

    @Test
    fun `an admin can watch any match`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()
        val match = aMatch()
        upload(client, match)

        val read = client.get("/v1/matches/${match.id}/replay") {
            header("X-Admin-Token", ADMIN_TOKEN)
        }
        assertEquals(HttpStatusCode.OK, read.status)
    }

    @Test
    fun `saving is capped and the cap is per player`() = testApplication {
        val deps = deps(ReplayPolicy(savedPerPlayer = 1))
        application { rankedApi(deps) }
        val client = jsonClient()
        val annaToken = token(anna, deps)

        val first = aMatch()
        upload(client, first)
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/matches/${first.id}/replay/save") {
                header("Authorization", "Bearer $annaToken")
            }.status,
        )

        val second = aMatch()
        upload(client, second)
        assertEquals(
            HttpStatusCode.Conflict,
            client.post("/v1/matches/${second.id}/replay/save") {
                header("Authorization", "Bearer $annaToken")
            }.status,
        )

        // Ben's own quota is untouched by Anna filling hers.
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/matches/${second.id}/replay/save") {
                header("Authorization", "Bearer ${token(ben, deps)}")
            }.status,
        )

        val mine: ReplayListResponse = client.get("/v1/players/me/replays") {
            header("Authorization", "Bearer $annaToken")
        }.body()
        assertEquals(1, mine.replays.size)
        assertTrue(mine.quota.full)
        assertTrue(mine.replays.first().saved)
        // A pinned replay reports no expiry: telling the player it expires in
        // three days is exactly the wrong thing to say about one they saved.
        assertEquals(null, mine.replays.first().expiresAt)
    }

    @Test
    fun `a byte quota refuses a save the file count would have allowed`() = testApplication {
        // Room for ten files and for less than one recording: the point of counting
        // bytes at all is that a packet capture is three orders of magnitude larger
        // than the sample track the file count was chosen for.
        val deps = deps(ReplayPolicy(savedPerPlayer = 10, savedBytesPerPlayer = (packets.size - 1).toLong()))
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()
        upload(client, match)

        val response = client.post("/v1/matches/${match.id}/replay/save") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }
        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("MB"))
    }

    @Test
    fun `unsaving drops only the caller's copy`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()
        upload(client, match)

        client.post("/v1/matches/${match.id}/replay/save") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }
        client.post("/v1/matches/${match.id}/replay/save") {
            header("Authorization", "Bearer ${token(ben, deps)}")
        }
        client.delete("/v1/matches/${match.id}/replay/save") {
            header("Authorization", "Bearer ${token(anna, deps)}")
        }

        val record = replays.get(match.id)!!
        assertFalse(anna in record.savedBy)
        assertTrue(ben in record.savedBy)
    }

    @Test
    fun `reporting a match holds its replay for review`() = testApplication {
        val deps = deps()
        application { rankedApi(deps) }
        val client = jsonClient()
        val match = aMatch()
        upload(client, match)

        assertFalse(replays.get(match.id)!!.underReview)

        val filed = client.post("/v1/reports") {
            header("Authorization", "Bearer ${token(anna, deps)}")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(ReportRequest.serializer(), ReportRequest(match.id.toString(), "hacking")))
        }
        assertEquals(HttpStatusCode.OK, filed.status)

        val held = replays.get(match.id)!!
        assertTrue(held.underReview, "a reported match's recording is what a moderator judges the report on")
        // Nothing is pinning it for a player, and it is still not sweepable.
        assertTrue(replays.pruneExpired(Instant.now().plusSeconds(365 * 24 * 3600)).isEmpty())

        val listed = client.get("/v1/admin/replays") { header("X-Admin-Token", ADMIN_TOKEN) }
        assertEquals(HttpStatusCode.OK, listed.status)
    }

    @Test
    fun `sweeping an expired replay reports it so the packets go too`() = testApplication {
        application { rankedApi(deps()) }
        val client = jsonClient()
        val match = aMatch()
        upload(client, match)

        assertTrue(replays.pruneExpired(Instant.now()).isEmpty(), "not due yet")

        // The ids are the point: the bytes live outside the store, and a pruner
        // that only dropped rows would leave them on disk forever.
        val dropped = replays.pruneExpired(Instant.now().plusSeconds(365 * 24 * 3600))
        assertEquals(listOf(match.id), dropped)
        assertEquals(null, replays.get(match.id))

        val sweep = dev.yabranked.backend.store.ReplaySweep(replays, blobs)
        sweep.sweep()
        blobs.delete(match.id)
        assertEquals(0, blobs.totalBytes(match.id))
    }

    private suspend fun appendChunk(client: HttpClient, match: MatchRecord, offset: Long, bytes: ByteArray) =
        client.post("/v1/internal/matches/${match.id}/replay/streams/0?offset=$offset") {
            header("Authorization", "Bearer ${match.serverToken}")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }

    private suspend fun putMeta(client: HttpClient, match: MatchRecord, complete: Boolean) =
        client.post("/v1/internal/matches/${match.id}/replay?complete=$complete") {
            header("Authorization", "Bearer ${match.serverToken}")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(MatchReplayMeta.serializer(), aMeta(match.id)))
        }

    /** What the agent does: packets first, then the index that describes them. */
    private suspend fun upload(client: HttpClient, match: MatchRecord) {
        appendChunk(client, match, offset = 0, bytes = packets)
        putMeta(client, match, complete = true)
    }

    private companion object {
        const val ADMIN_TOKEN = "admin-secret"
    }
}
