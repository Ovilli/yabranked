package dev.yabranked.backend.api

import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryModeStatsStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.proto.FriendListResponse
import dev.yabranked.proto.FriendRequestCreate
import dev.yabranked.proto.LeaderboardCategory
import dev.yabranked.proto.LeaderboardResponse
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.PlayerProfile
import dev.yabranked.proto.PrivacySettings
import dev.yabranked.proto.ProfileUpdate
import dev.yabranked.proto.RecentPlayer
import dev.yabranked.proto.SessionRequest
import dev.yabranked.proto.SessionResponse
import dev.yabranked.proto.Visibility
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The social endpoints end to end: the friend rules as an HTTP caller sees
 * them, the privacy block actually gating a profile read, and the per-category
 * leaderboards.
 */
class SocialApiTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val modeStats = InMemoryModeStatsStore()
    private val seasons = SeasonService()
    private val matchService = MatchService(
        players, matches, EloRatingSystem(), seasons, modeStats = modeStats,
    )
    private val queueService = QueueService(MatchmakingQueue(), matchService)

    /** One instance for the whole test: routes and services must share state. */
    private val deps = ApiDependencies(
        verifier = FakeSessionVerifier(),
        players = players,
        matches = matches,
        matchService = matchService,
        queueService = queueService,
        seasons = seasons,
    )

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    private fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json(json) }
    }

    private suspend fun signIn(client: HttpClient, name: String): SessionResponse =
        client.post("/v1/auth/session") {
            contentType(ContentType.Application.Json)
            setBody(SessionRequest(name, "serverid"))
        }.body()

    /** Plays and settles one 1v1 so the two accounts count as having met. */
    private suspend fun playTogether(client: HttpClient, a: SessionResponse, b: SessionResponse) {
        val match = matchService.createTeamMatch(
            MatchService.TeamMatchRequest(
                format = MatchFormat.LOCKOUT_1V1,
                teams = listOf(
                    listOf(UUID.fromString(a.profile.uuid)),
                    listOf(UUID.fromString(b.profile.uuid)),
                ),
            )
        )
        client.post("/v1/internal/matches/result") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${match.serverToken}")
            setBody(MatchResultReport(match.id.toString(), MatchOutcome.TEAM_A_WIN, 600, 10, 4))
        }
    }

    @Test
    fun `a friend request goes through without a shared match`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()

        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")

        // Signing in is the only prerequisite — no match needed.
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/friends/requests") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${alice.token}")
                setBody(FriendRequestCreate(uuid = bob.profile.uuid))
            }.status,
        )
        client.get("/v1/friends") { header("Authorization", "Bearer ${bob.token}") }
            .body<FriendListResponse>().incoming.single().let { pending ->
                client.delete("/v1/friends/requests/${pending.id}") {
                    header("Authorization", "Bearer ${bob.token}")
                }
            }

        playTogether(client, alice, bob)

        val recent: List<RecentPlayer> = client.get("/v1/friends/recent") {
            header("Authorization", "Bearer ${alice.token}")
        }.body()
        assertEquals(listOf(bob.profile.uuid), recent.map { it.player.uuid })
        assertTrue(recent.single().requestable)

        val sent = client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(uuid = bob.profile.uuid))
        }
        assertEquals(HttpStatusCode.OK, sent.status)

        val incoming: FriendListResponse = client.get("/v1/friends") {
            header("Authorization", "Bearer ${bob.token}")
        }.body()
        val request = incoming.incoming.single()
        assertEquals(alice.profile.uuid, request.from.uuid)

        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/friends/requests/${request.id}/accept") {
                header("Authorization", "Bearer ${bob.token}")
            }.status,
        )

        val friends: FriendListResponse = client.get("/v1/friends") {
            header("Authorization", "Bearer ${alice.token}")
        }.body()
        assertEquals(listOf(bob.profile.uuid), friends.friends.map { it.player.uuid })
        assertTrue(friends.incoming.isEmpty())
    }

    @Test
    fun `a friend can be added by name`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")

        // Case-insensitive, and no shared match anywhere in the story.
        val sent = client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(name = "bOb"))
        }
        assertEquals(HttpStatusCode.OK, sent.status)
        assertEquals(
            alice.profile.uuid,
            client.get("/v1/friends") { header("Authorization", "Bearer ${bob.token}") }
                .body<FriendListResponse>().incoming.single().from.uuid,
        )

        val unknown = client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(name = "NeverPlayed"))
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status, "a name nobody holds is a 404")
    }

    @Test
    fun `rejecting a request leaves the two unfriended and re-requestable`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")

        client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(uuid = bob.profile.uuid))
        }
        val id = client.get("/v1/friends") { header("Authorization", "Bearer ${bob.token}") }
            .body<FriendListResponse>().incoming.single().id

        assertEquals(
            HttpStatusCode.OK,
            client.delete("/v1/friends/requests/$id") {
                header("Authorization", "Bearer ${bob.token}")
            }.status,
        )

        val bobsView: FriendListResponse = client.get("/v1/friends") {
            header("Authorization", "Bearer ${bob.token}")
        }.body()
        assertTrue(bobsView.incoming.isEmpty(), "a rejected request is gone, not merely hidden")
        assertTrue(bobsView.friends.isEmpty(), "rejecting must not create the friendship")

        val alicesView: FriendListResponse = client.get("/v1/friends") {
            header("Authorization", "Bearer ${alice.token}")
        }.body()
        assertTrue(alicesView.outgoing.isEmpty(), "the sender's pending list clears too")

        // A rejection is not a block: asking again is allowed (and rate-limited).
        assertEquals(
            HttpStatusCode.OK,
            client.post("/v1/friends/requests") {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer ${alice.token}")
                setBody(FriendRequestCreate(uuid = bob.profile.uuid))
            }.status,
        )
    }

    @Test
    fun `a third party cannot answer someone else's request`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")
        val mallory = signIn(client, "Mallory")

        client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(uuid = bob.profile.uuid))
        }
        val id = client.get("/v1/friends") { header("Authorization", "Bearer ${bob.token}") }
            .body<FriendListResponse>().incoming.single().id

        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/v1/friends/requests/$id/accept") {
                header("Authorization", "Bearer ${mallory.token}")
            }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            client.delete("/v1/friends/requests/$id") {
                header("Authorization", "Bearer ${mallory.token}")
            }.status,
        )
        // still pending, untouched
        assertEquals(
            1,
            client.get("/v1/friends") { header("Authorization", "Bearer ${bob.token}") }
                .body<FriendListResponse>().incoming.size,
        )
    }

    @Test
    fun `removing a friend is symmetric`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")

        client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(uuid = bob.profile.uuid))
        }
        val id = client.get("/v1/friends") { header("Authorization", "Bearer ${bob.token}") }
            .body<FriendListResponse>().incoming.single().id
        client.post("/v1/friends/requests/$id/accept") { header("Authorization", "Bearer ${bob.token}") }

        assertEquals(
            HttpStatusCode.OK,
            client.delete("/v1/friends/${bob.profile.uuid}") {
                header("Authorization", "Bearer ${alice.token}")
            }.status,
        )
        val bobsList: FriendListResponse = client.get("/v1/friends") {
            header("Authorization", "Bearer ${bob.token}")
        }.body()
        assertTrue(bobsList.friends.isEmpty(), "unfriending removes both directions")
    }

    @Test
    fun `a friend request to someone who refuses them is rejected`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")

        client.put("/v1/players/me") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${bob.token}")
            setBody(ProfileUpdate(privacy = PrivacySettings(allowFriendRequests = false)))
        }

        val refused = client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(uuid = bob.profile.uuid))
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
    }

    @Test
    fun `privacy gates the profile a stranger sees`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")
        playTogether(client, alice, bob)

        client.put("/v1/players/me") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${bob.token}")
            setBody(
                ProfileUpdate(
                    country = "de",
                    privacy = PrivacySettings(
                        showCountry = Visibility.FRIENDS,
                        showRating = Visibility.NOBODY,
                        showStreak = Visibility.NOBODY,
                    ),
                )
            )
        }

        val stranger: PlayerProfile = client.get("/v1/players/${bob.profile.uuid}").body()
        assertNull(stranger.country, "a friends-only flag is not shown to a stranger")
        assertEquals(0, stranger.rating, "a hidden rating is zeroed")
        assertNull(stranger.peakRating)
        assertNull(stranger.currentStreak)
        assertTrue(stranger.tier.isNotBlank(), "the tier is never hidden — matchmaking is public")
        assertFalse(stranger.isFriend)

        // Bob's own view is unredacted
        val own: PlayerProfile = client.get("/v1/players/${bob.profile.uuid}") {
            header("Authorization", "Bearer ${bob.token}")
        }.body()
        assertEquals("de", own.country)
        assertNotNull(own.currentStreak)
        assertEquals(Visibility.NOBODY, own.privacy.showRating)
    }

    @Test
    fun `a friend sees the friends-only fields`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")
        playTogether(client, alice, bob)

        client.put("/v1/players/me") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${bob.token}")
            setBody(ProfileUpdate(country = "de", privacy = PrivacySettings(showCountry = Visibility.FRIENDS)))
        }
        // become friends
        client.post("/v1/friends/requests") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer ${alice.token}")
            setBody(FriendRequestCreate(uuid = bob.profile.uuid))
        }
        val id = client.get("/v1/friends") { header("Authorization", "Bearer ${bob.token}") }
            .body<FriendListResponse>().incoming.single().id
        client.post("/v1/friends/requests/$id/accept") { header("Authorization", "Bearer ${bob.token}") }

        val seen: PlayerProfile = client.get("/v1/players/${bob.profile.uuid}") {
            header("Authorization", "Bearer ${alice.token}")
        }.body()
        assertEquals("de", seen.country)
        assertTrue(seen.isFriend)
    }

    @Test
    fun `the profile carries a per-mode breakdown`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")
        playTogether(client, alice, bob)

        val profile: PlayerProfile = client.get("/v1/players/${alice.profile.uuid}") {
            header("Authorization", "Bearer ${alice.token}")
        }.body()
        val mode = profile.modes.single()
        assertEquals(MatchFormat.LOCKOUT_1V1, mode.format)
        assertEquals(1, mode.matchesPlayed)
        assertEquals(1, mode.wins)
        assertEquals(600, mode.playtimeSeconds)
        assertEquals(1, profile.currentStreak)
    }

    @Test
    fun `every mode has a leaderboard category`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()

        val categories: List<LeaderboardCategory> = client.get("/v1/leaderboards").body()
        val ids = categories.map { it.id }
        assertTrue("overall" in ids)
        assertTrue("endorsements" in ids)
        assertTrue("playtime" in ids)
        assertTrue("streak" in ids)
        for (format in MatchFormat.entries.filter { it.playable }) {
            assertTrue(format.wire in ids, "${format.displayName} has no board")
        }
        // a rated mode's board sorts on rating, an unrated one cannot
        assertEquals("rating", categories.single { it.id == MatchFormat.RANKED_2V2.wire }.metric)
        assertEquals("wins", categories.single { it.id == MatchFormat.CASUAL_2V2.wire }.metric)
    }

    @Test
    fun `a mode board lists the players who played that mode`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        val alice = signIn(client, "Alice")
        val bob = signIn(client, "Bob")
        playTogether(client, alice, bob)

        val board: LeaderboardResponse =
            client.get("/v1/leaderboards/${MatchFormat.LOCKOUT_1V1.wire}?limit=10").body()
        assertEquals(MatchFormat.LOCKOUT_1V1, board.category.format)
        // minMatches is the placement count, and one match is not enough to be
        // ranked — the same rule the main ladder uses.
        assertTrue(board.rows.isEmpty())

        val playtime: LeaderboardResponse = client.get("/v1/leaderboards/playtime?limit=10").body()
        assertEquals(setOf(alice.profile.uuid, bob.profile.uuid), playtime.rows.map { it.player.uuid }.toSet())
        assertTrue(playtime.rows.all { it.numericValue == 600L })
    }

    @Test
    fun `an unknown leaderboard is a 404`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        assertEquals(HttpStatusCode.NotFound, client.get("/v1/leaderboards/nope").status)
    }

    @Test
    fun `the social endpoints refuse an unauthenticated caller`() = testApplication {
        application { rankedApi(deps) }
        val client = jsonClient()
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/friends").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/friends/recent").status)
        assertEquals(
            HttpStatusCode.Unauthorized,
            client.delete("/v1/friends/${UUID.randomUUID()}").status,
        )
    }
}
