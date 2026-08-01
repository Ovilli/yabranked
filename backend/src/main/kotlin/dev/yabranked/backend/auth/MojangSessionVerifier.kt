package dev.yabranked.backend.auth

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import java.io.IOException
import java.util.UUID

data class VerifiedPlayer(
    val uuid: UUID,
    val name: String,
)

/**
 * Verifies that a connecting player owns the Minecraft account they claim.
 *
 * Flow (same handshake a Minecraft server performs):
 * 1. Client mod calls Mojang `joinServer` with a serverId we hand out.
 * 2. Client tells us its username + serverId.
 * 3. We call `hasJoined`; Mojang confirms and returns the account UUID.
 */
interface SessionVerifier {
    suspend fun verify(username: String, serverId: String): VerifiedPlayer?
}

class MojangSessionVerifier(
    private val http: HttpClient,
    private val baseUrl: String = "https://sessionserver.mojang.com",
) : SessionVerifier {

    @Serializable
    private data class HasJoinedResponse(val id: String, val name: String)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun verify(username: String, serverId: String): VerifiedPlayer? {
        val response = hasJoined(username, serverId)
        if (response.status != HttpStatusCode.OK) return null

        val body = json.decodeFromString<HasJoinedResponse>(response.bodyAsText())
        return VerifiedPlayer(
            uuid = undashedUuid(body.id),
            name = body.name,
        )
    }

    /**
     * `hasJoined`, retried on a transport failure.
     *
     * The client pools keep-alive connections and Mojang closes idle ones from
     * its side, so the first request after a quiet spell can be written to a
     * socket that is already dead. That surfaces as an [IOException] — usually
     * `EOFException: the server prematurely closed the connection` — with no
     * response line at all, and it failed the login with a 500 rather than
     * being the retryable nothing it is. The request is a GET and carries no
     * side effect, so replaying it is safe.
     *
     * Only transport failures are retried. A reachable Mojang that answers
     * "no" is a [HttpResponse] and returns on the first attempt.
     */
    private suspend fun hasJoined(username: String, serverId: String): HttpResponse {
        var last: IOException? = null
        repeat(ATTEMPTS) { attempt ->
            try {
                return http.get("$baseUrl/session/minecraft/hasJoined") {
                    parameter("username", username)
                    parameter("serverId", serverId)
                }
            } catch (e: IOException) {
                last = e
                if (attempt < ATTEMPTS - 1) delay(RETRY_DELAY_MILLIS)
            }
        }
        throw last!!
    }

    companion object {
        /** One retry: a stale pooled socket fails once, and a real outage is not worth waiting out. */
        private const val ATTEMPTS = 2
        private const val RETRY_DELAY_MILLIS = 150L

        fun undashedUuid(id: String): UUID {
            require(id.length == 32) { "expected undashed uuid, got '$id'" }
            val dashed = buildString {
                append(id, 0, 8); append('-')
                append(id, 8, 12); append('-')
                append(id, 12, 16); append('-')
                append(id, 16, 20); append('-')
                append(id, 20, 32)
            }
            return UUID.fromString(dashed)
        }
    }
}

/**
 * Accepts any player; for tests and local development only.
 * Uses the vanilla offline-mode UUID formula so identities line up with an
 * offline-mode (online-mode=false) match server during local 2-client tests.
 */
class FakeSessionVerifier : SessionVerifier {
    override suspend fun verify(username: String, serverId: String): VerifiedPlayer =
        VerifiedPlayer(
            uuid = UUID.nameUUIDFromBytes("OfflinePlayer:$username".toByteArray(Charsets.UTF_8)),
            name = username,
        )
}
