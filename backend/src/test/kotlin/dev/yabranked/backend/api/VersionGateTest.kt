package dev.yabranked.backend.api

import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VersionGateTest {

    @Test
    fun `versionAtLeast handles common shapes`() {
        assertTrue(versionAtLeast("0.1.0", "0.1.0"))
        assertTrue(versionAtLeast("0.2.0", "0.1.9"))
        assertTrue(versionAtLeast("1.0.0", "0.9.9"))
        assertFalse(versionAtLeast("0.1.0", "0.1.1"))
        assertFalse(versionAtLeast("0.1", "0.1.1"))
        assertTrue(versionAtLeast("0.1.1+build5", "0.1.1"))
    }

    @Test
    fun `auth rejects old or missing client version when gate is set`() = testApplication {
        val players = InMemoryPlayerStore()
        val matches = InMemoryMatchStore()
        val matchService = MatchService(players, matches, EloRatingSystem())
        application {
            rankedApi(
                ApiDependencies(
                    verifier = FakeSessionVerifier(),
                    players = players,
                    matches = matches,
                    matchService = matchService,
                    queueService = QueueService(MatchmakingQueue(), matchService),
                    minClientVersion = "0.2.0",
                )
            )
        }
        val client = createClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }

        suspend fun attempt(version: String?): HttpStatusCode =
            client.post("/v1/auth/session") {
                contentType(ContentType.Application.Json)
                setBody(SessionRequest("Player", "sid", version))
            }.status

        assertEquals(HttpStatusCode.UpgradeRequired, attempt(null))
        assertEquals(HttpStatusCode.UpgradeRequired, attempt("0.1.9"))
        assertEquals(HttpStatusCode.OK, attempt("0.2.0"))
        assertEquals(HttpStatusCode.OK, attempt("0.3.0"))
    }
}
