package dev.yabranked.backend.mock

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.MatchTeam
import dev.yabranked.proto.QueueClientMessage
import dev.yabranked.proto.QueueServerMessage
import dev.yabranked.proto.SessionRequest
import dev.yabranked.proto.SessionResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.receiveDeserialized
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class TokenResponse(val token: String)

/**
 * CLI mock client: authenticates two fake players against a backend running
 * with --fake-auth, queues both, waits for the match, then reports a result
 * the way the match-server agent would (fetching the per-match server token
 * from the debug endpoint, which only exists in fake-auth mode).
 *
 * Usage:
 *   1. Run backend: ./gradlew :backend:run --args="--fake-auth"
 *   2. Run mock:    ./gradlew :backend:runMock
 */
fun main() = runBlocking {
    val baseUrl = System.getenv("YABRANKED_URL") ?: "http://localhost:8080"
    val wsUrl = baseUrl.replaceFirst("http", "ws")

    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
    val http = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets) {
            contentConverter = KotlinxWebsocketSerializationConverter(json)
        }
    }

    suspend fun authenticate(name: String): SessionResponse {
        val response = http.post("$baseUrl/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest(username = name, serverId = "mock"))
        }
        return response.body()
    }

    suspend fun queueUntilMatched(session: SessionResponse): QueueServerMessage.MatchFound {
        var found: QueueServerMessage.MatchFound? = null
        http.webSocket("$wsUrl/v1/queue?token=${session.token}") {
            sendSerialized<QueueClientMessage>(QueueClientMessage.JoinQueue(MatchFormat.LOCKOUT_1V1))
            while (found == null) {
                when (val message = receiveDeserialized<QueueServerMessage>()) {
                    is QueueServerMessage.QueueState ->
                        println("[${session.profile.name}] queued: pos=${message.position}/${message.playersInQueue} waited=${message.waitedSeconds}s")
                    is QueueServerMessage.MatchFound -> {
                        println("[${session.profile.name}] MATCH FOUND: ${message.matchId} vs ${message.opponent.name} @ ${message.serverAddress}")
                        found = message
                    }
                    is QueueServerMessage.QueueError ->
                        error("queue error: ${message.message}")
                    is QueueServerMessage.QueueCancelled ->
                        error("queue cancelled: ${message.reason}")
                }
            }
        }
        return found!!
    }

    // override with the usernames of real clients for the local 2-client test
    val nameA = System.getenv("YABRANKED_MOCK_PLAYER_A") ?: "AliceMock"
    val nameB = System.getenv("YABRANKED_MOCK_PLAYER_B") ?: "BobMock"

    println("== authenticating two fake players ==")
    val alice = authenticate(nameA)
    val bob = authenticate(nameB)
    println("alice: rating=${alice.profile.rating} placements=${alice.profile.placementMatchesRemaining}")
    println("bob:   rating=${bob.profile.rating} placements=${bob.profile.placementMatchesRemaining}")

    println("== queueing both ==")
    val (matchA, _) = coroutineScope {
        val a = async { queueUntilMatched(alice) }
        val b = async { queueUntilMatched(bob) }
        a.await() to b.await()
    }

    // fetch the per-match server token from the debug endpoint (fake-auth mode),
    // playing the role the orchestrator/agent will take over in Phase 2
    val serverToken = System.getenv("YABRANKED_SERVER_TOKEN")
        ?: runCatching {
            http.get("$baseUrl/v1/debug/matches/${matchA.matchId}/token")
                .body<TokenResponse>().token
        }.getOrNull()

    println("MATCH_ID=${matchA.matchId}")
    println("SERVER_TOKEN=${serverToken ?: "?"}")

    // YABRANKED_MOCK_SETTLE=0 leaves the match open so a real match server /
    // agent can settle it (used by the orchestration smoke test)
    if (System.getenv("YABRANKED_MOCK_SETTLE") == "0") {
        println("(mock settle disabled — leaving match open for an external agent)")
        http.close()
        return@runBlocking
    }

    if (serverToken != null) {
        println("== reporting result: Alice wins ==")
        // matchA is Alice's MatchFound message, so her team assignment decides the outcome value
        val aliceWins = when (matchA.team) {
            MatchTeam.TEAM_A -> MatchOutcome.TEAM_A_WIN
            MatchTeam.TEAM_B -> MatchOutcome.TEAM_B_WIN
        }
        val response = http.post("$baseUrl/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $serverToken")
            setBody(
                MatchResultReport(
                    matchId = matchA.matchId,
                    outcome = aliceWins,
                    durationSeconds = 1234,
                    teamAScore = 13,
                    teamBScore = 9,
                )
            )
        }
        println("settle response: ${response.status} ${response.bodyAsText()}")

        val profile = http.get("$baseUrl/v1/players/${alice.profile.uuid}").bodyAsText()
        println("alice after: $profile")
    } else {
        println("(no YABRANKED_SERVER_TOKEN set — skipping result settle; see backend log for the match)")
    }

    http.close()
}
