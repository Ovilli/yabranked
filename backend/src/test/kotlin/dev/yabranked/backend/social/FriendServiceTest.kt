package dev.yabranked.backend.social

import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryFriendStore
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import dev.yabranked.proto.PrivacySettings
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendServiceTest {

    private val players = InMemoryPlayerStore()
    private val matches = InMemoryMatchStore()
    private val friends = InMemoryFriendStore()
    private val seasons = SeasonService()
    private var now = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }

    private val service = FriendService(friends, players, matches, seasons, clock)

    private fun player(name: String, privacy: PrivacySettings = PrivacySettings()): UUID {
        val uuid = UUID.randomUUID()
        players.upsertPlayer(
            PlayerRecord(uuid = uuid, name = name, createdAt = now, privacy = privacy)
        )
        return uuid
    }

    /** Records a finished match with [roster] as its side-ordered teams. */
    private fun playedTogether(vararg roster: List<UUID>) {
        val sides = roster.toList()
        matches.insert(
            MatchRecord(
                id = UUID.randomUUID(),
                season = seasons.currentSeason,
                format = MatchFormat.LOCKOUT_1V1,
                settings = MatchSettings(MatchFormat.LOCKOUT_1V1, 1L, 2L, 3600),
                playerA = sides[0].first(),
                playerB = sides[1].first(),
                status = MatchStatus.COMPLETED,
                serverToken = "t",
                outcome = MatchOutcome.TEAM_A_WIN,
                ratingABefore = 1000,
                ratingBBefore = 1000,
                ratingAAfter = 1016,
                ratingBAfter = 984,
                createdAt = now,
                completedAt = now,
                teams = if (sides.all { it.size == 1 }) emptyList() else sides,
            )
        )
    }

    @Test
    fun `anyone who has used the mod can be requested`() {
        val alice = player("Alice")
        val stranger = player("Stranger")

        // No shared match, and none needed — having a player row is the rule.
        assertNull(service.canRequest(alice, stranger))
        assertIs<FriendService.RequestResult.Sent>(service.request(alice, stranger))
    }

    @Test
    fun `an account that has never used the mod cannot be requested`() {
        val alice = player("Alice")
        val ghost = UUID.randomUUID()

        assertNotNull(service.canRequest(alice, ghost))
        assertIs<FriendService.RequestResult.Rejected>(service.request(alice, ghost))
    }

    @Test
    fun `recent players is a shortcut list, not a restriction`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val dave = player("Dave")
        playedTogether(listOf(alice, bob), listOf(carol, dave))

        assertTrue(service.hasPlayedWith(alice, bob), "a teammate is someone you played with")
        assertTrue(service.hasPlayedWith(alice, dave), "so is an opponent")
        assertEquals(
            setOf(bob, carol, dave),
            service.recentPlayers(alice).map { it.first }.toSet(),
        )
        // and someone absent from that list is still requestable
        val outsider = player("Outsider")
        assertNull(service.canRequest(alice, outsider))
    }

    @Test
    fun `the target's toggle refuses the request outright`() {
        val alice = player("Alice")
        val bob = player("Bob", PrivacySettings(allowFriendRequests = false))
        playedTogether(listOf(alice), listOf(bob))

        val result = service.request(alice, bob)
        assertIs<FriendService.RequestResult.Rejected>(result)
        assertTrue(service.incoming(bob).isEmpty(), "a refused request must not be queued for later")
    }

    @Test
    fun `a banned player cannot be requested`() {
        val alice = player("Alice")
        val bob = player("Bob")
        playedTogether(listOf(alice), listOf(bob))
        players.upsertPlayer(players.getPlayer(bob)!!.copy(bannedAt = now))

        assertIs<FriendService.RequestResult.Rejected>(service.request(alice, bob))
    }

    @Test
    fun `only one request may be pending per pair`() {
        val alice = player("Alice")
        val bob = player("Bob")

        assertIs<FriendService.RequestResult.Sent>(service.request(alice, bob))
        assertIs<FriendService.RequestResult.Rejected>(service.request(alice, bob))
        assertEquals(1, service.incoming(bob).size)
    }

    @Test
    fun `requesting someone who already requested you accepts theirs`() {
        val alice = player("Alice")
        val bob = player("Bob")
        playedTogether(listOf(alice), listOf(bob))

        assertIs<FriendService.RequestResult.Sent>(service.request(bob, alice))
        assertIs<FriendService.RequestResult.AutoAccepted>(service.request(alice, bob))

        assertTrue(service.areFriends(alice, bob))
        assertTrue(service.incoming(alice).isEmpty())
        assertTrue(service.incoming(bob).isEmpty())
    }

    @Test
    fun `only the addressee may accept`() {
        val alice = player("Alice")
        val bob = player("Bob")
        playedTogether(listOf(alice), listOf(bob))
        val request = assertIs<FriendService.RequestResult.Sent>(service.request(alice, bob)).request

        // the sender accepting their own request would be a way to add anyone
        assertNull(service.accept(alice, request.id))
        assertFalse(service.areFriends(alice, bob))

        assertEquals(alice, service.accept(bob, request.id))
        assertTrue(service.areFriends(alice, bob))
    }

    @Test
    fun `accepting is symmetric and recorded once`() {
        val alice = player("Alice")
        val bob = player("Bob")
        playedTogether(listOf(alice), listOf(bob))
        val request = assertIs<FriendService.RequestResult.Sent>(service.request(alice, bob)).request
        service.accept(bob, request.id)

        assertTrue(service.areFriends(bob, alice))
        assertEquals(listOf(bob), service.friendsOf(alice))
        assertEquals(listOf(alice), service.friendsOf(bob))
    }

    @Test
    fun `an expired request cannot be accepted`() {
        val alice = player("Alice")
        val bob = player("Bob")
        playedTogether(listOf(alice), listOf(bob))
        val request = assertIs<FriendService.RequestResult.Sent>(service.request(alice, bob)).request

        now = now.plus(Duration.ofDays(15))

        assertTrue(service.incoming(bob).isEmpty(), "an expired request is not shown")
        assertNull(service.accept(bob, request.id))
        assertFalse(service.areFriends(alice, bob))
    }

    @Test
    fun `dismiss works for both the sender and the addressee`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        playedTogether(listOf(alice), listOf(bob))
        playedTogether(listOf(alice), listOf(carol))

        val toBob = assertIs<FriendService.RequestResult.Sent>(service.request(alice, bob)).request
        assertTrue(service.dismiss(bob, toBob.id), "the addressee may decline")

        val toCarol = assertIs<FriendService.RequestResult.Sent>(service.request(alice, carol)).request
        assertTrue(service.dismiss(alice, toCarol.id), "the sender may cancel")

        // and an unrelated player may do neither
        val other = assertIs<FriendService.RequestResult.Sent>(service.request(alice, bob)).request
        assertFalse(service.dismiss(carol, other.id))
    }

    @Test
    fun `removing a friend also clears any request between them`() {
        val alice = player("Alice")
        val bob = player("Bob")
        playedTogether(listOf(alice), listOf(bob))
        val request = assertIs<FriendService.RequestResult.Sent>(service.request(alice, bob)).request
        service.accept(bob, request.id)

        assertTrue(service.remove(alice, bob))
        assertFalse(service.areFriends(alice, bob))
        assertTrue(service.incoming(bob).isEmpty())
        assertTrue(service.outgoing(alice).isEmpty())
    }

    @Test
    fun `the friend cap is enforced on both sides`() {
        val small = FriendService(friends, players, matches, seasons, clock, maxFriends = 1)
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        playedTogether(listOf(alice), listOf(bob))
        playedTogether(listOf(alice), listOf(carol))

        friends.addFriend(alice, bob, now)
        assertNotNull(small.canRequest(alice, carol), "a full list refuses new requests")
        assertNotNull(small.canRequest(carol, alice), "and so does a full target list")
    }

    @Test
    fun `recent players lists everyone you shared a match with, newest first`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        playedTogether(listOf(alice), listOf(bob))
        now = now.plusSeconds(60)
        playedTogether(listOf(alice), listOf(carol))

        val recent = service.recentPlayers(alice).map { it.first }
        assertEquals(listOf(carol, bob), recent)
        assertTrue(alice !in recent, "you are not your own recent player")
    }

    @Test
    fun `you cannot friend yourself`() {
        val alice = player("Alice")
        assertNotNull(service.canRequest(alice, alice))
        assertIs<FriendService.RequestResult.Rejected>(service.request(alice, alice))
    }
}
