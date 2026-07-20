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
        val response: HttpResponse = http.get("$baseUrl/session/minecraft/hasJoined") {
            parameter("username", username)
            parameter("serverId", serverId)
        }
        if (response.status != HttpStatusCode.OK) return null

        val body = json.decodeFromString<HasJoinedResponse>(response.bodyAsText())
        return VerifiedPlayer(
            uuid = undashedUuid(body.id),
            name = body.name,
        )
    }

    companion object {
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
