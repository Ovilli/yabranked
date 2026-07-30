package dev.yabranked.backend.social

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.PartyMode
import dev.yabranked.proto.PartyOptions
import dev.yabranked.proto.PartyServerMessage
import dev.yabranked.proto.PartyView
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.PresenceState
import dev.yabranked.proto.TeamAssignment
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The party system's safety properties, which is where most of its value is:
 * a party that can be left in a half-state, queued at the wrong size, or
 * mutated by a non-leader is worse than no party system at all.
 */
class PartyServiceTest {

    private class TestPlayer(
        val name: String,
        var allowInvites: Boolean = true,
        var friendsOnly: Boolean = false,
        var banned: Boolean = false,
        var rating: Int = 1000,
    )

    private val roster = ConcurrentHashMap<UUID, TestPlayer>()
    private val friendships = mutableSetOf<Set<UUID>>()
    private val presence = Presence()

    private val service = PartyService(
        lookup = { uuid, _ ->
            roster[uuid]?.let {
                PartyPlayerSnapshot(
                    ref = PlayerRef(uuid.toString(), it.name),
                    rating = it.rating,
                    tier = "Gold I",
                    allowInvites = it.allowInvites,
                    friendsOnly = it.friendsOnly,
                    banned = it.banned,
                )
            }
        },
        presence = presence,
        areFriends = { a, b -> setOf(a, b) in friendships },
    )

    /** Everything each player's socket was pushed, oldest first. */
    private val inbox = ConcurrentHashMap<UUID, MutableList<PartyServerMessage>>()

    private fun player(name: String, connect: Boolean = true): UUID {
        val uuid = UUID.randomUUID()
        roster[uuid] = TestPlayer(name)
        if (connect) {
            val messages = inbox.getOrPut(uuid) { mutableListOf() }
            service.connect(uuid) { synchronized(messages) { messages += it } }
        }
        return uuid
    }

    private fun messages(uuid: UUID): List<PartyServerMessage> =
        inbox[uuid]?.let { synchronized(it) { it.toList() } } ?: emptyList()

    private fun latestView(uuid: UUID): PartyView? =
        messages(uuid).filterIsInstance<PartyServerMessage.State>().lastOrNull()?.party

    /** Puts [leader] and [others] in one party, ignoring the pushes it generates. */
    private fun partyOf(leader: UUID, vararg others: UUID): UUID {
        service.create(leader)
        for (other in others) {
            assertIs<PartyService.Result.Ok>(service.invite(leader, other))
            val partyId = service.partyIdOf(leader)!!
            assertIs<PartyService.Result.Ok>(service.acceptInvite(other, partyId))
        }
        return service.partyIdOf(leader)!!
    }

    @Test
    fun `creating a party makes the creator its leader`() {
        val alice = player("Alice")
        val result = service.create(alice)
        val view = assertIs<PartyService.Result.Ok>(result).party
        assertNotNull(view)
        assertEquals(alice.toString(), view.leader)
        assertEquals(1, view.members.size)
        assertTrue(service.isLeader(alice))
    }

    @Test
    fun `create is idempotent`() {
        val alice = player("Alice")
        service.create(alice)
        val first = service.partyIdOf(alice)
        service.create(alice)
        assertEquals(first, service.partyIdOf(alice))
    }

    @Test
    fun `invite is refused when the target disabled party invites`() {
        val alice = player("Alice")
        val bob = player("Bob")
        roster[bob]!!.allowInvites = false

        val result = service.invite(alice, bob)
        assertIs<PartyService.Result.Rejected>(result)
        assertTrue(messages(bob).none { it is PartyServerMessage.Invited }, "no invite should be delivered")
    }

    @Test
    fun `friends-only invites are refused from strangers and accepted from friends`() {
        val alice = player("Alice")
        val bob = player("Bob")
        roster[bob]!!.friendsOnly = true

        assertIs<PartyService.Result.Rejected>(service.invite(alice, bob))

        friendships += setOf(alice, bob)
        assertIs<PartyService.Result.Ok>(service.invite(alice, bob))
        assertTrue(messages(bob).any { it is PartyServerMessage.Invited })
    }

    @Test
    fun `a member cannot invite into a leader-run party`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val partyId = partyOf(alice, bob)
        service.setOptions(
            alice,
            PartyOptions(format = MatchFormat.RANKED_2V2, partyMode = PartyMode.PARTY_VS_PARTY),
        )

        assertIs<PartyService.Result.Rejected>(service.invite(bob, carol))
        assertEquals(2, service.membersOf(partyId).size)
    }

    @Test
    fun `the leader leaving disbands the whole party`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val partyId = partyOf(alice, bob, carol)

        service.leave(alice)

        assertNull(service.partyIdOf(alice))
        assertNull(service.partyIdOf(bob))
        assertNull(service.partyIdOf(carol))
        assertTrue(service.membersOf(partyId).isEmpty())
        for (member in listOf(bob, carol)) {
            assertTrue(
                messages(member).any { it is PartyServerMessage.Disbanded },
                "$member should have been told the party is gone",
            )
            assertNull(latestView(member), "$member should end with no party")
        }
    }

    @Test
    fun `a member leaving keeps the party alive for everyone else`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val partyId = partyOf(alice, bob, carol)

        service.leave(bob)

        assertEquals(setOf(alice, carol), service.membersOf(partyId).toSet())
        assertNull(service.partyIdOf(bob))
        assertNotNull(latestView(carol))
        assertEquals(2, latestView(carol)!!.members.size)
    }

    @Test
    fun `the last member leaving disbands the party`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)

        service.leave(bob)
        service.leave(alice)

        assertTrue(service.membersOf(partyId).isEmpty())
        assertNull(service.viewOf(partyId))
    }

    @Test
    fun `only the leader may kick, promote or change settings`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        partyOf(alice, bob, carol)

        assertIs<PartyService.Result.Rejected>(service.kick(bob, carol))
        assertIs<PartyService.Result.Rejected>(service.promote(bob, carol))
        assertIs<PartyService.Result.Rejected>(
            service.setOptions(bob, PartyOptions(format = MatchFormat.RANKED_2V2))
        )
        assertEquals(3, service.membersOf(service.partyIdOf(alice)!!).size)
        assertTrue(service.isLeader(alice))
    }

    @Test
    fun `promote hands over leadership`() {
        val alice = player("Alice")
        val bob = player("Bob")
        partyOf(alice, bob)

        assertIs<PartyService.Result.Ok>(service.promote(alice, bob))
        assertTrue(service.isLeader(bob))
        assertFalse(service.isLeader(alice))

        // and the new leader's leaving is now what disbands it
        service.leave(bob)
        assertNull(service.partyIdOf(alice))
    }

    @Test
    fun `a kicked player is removed and told why`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)

        assertIs<PartyService.Result.Ok>(service.kick(alice, bob))
        assertEquals(listOf(alice), service.membersOf(partyId))
        assertTrue(messages(bob).any { it is PartyServerMessage.Disbanded })
    }

    @Test
    fun `a party may not queue at the wrong size for its format`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val partyId = partyOf(alice, bob, carol)
        service.setOptions(alice, PartyOptions(format = MatchFormat.RANKED_2V2, ranked = true))

        // three players cannot be one side of a 2v2
        assertNotNull(service.viewOf(partyId)!!.startBlockedReason)
        assertFalse(service.setQueued(partyId, true))
        assertNull(service.resolveTeams(partyId))
    }

    @Test
    fun `a full party queues as one side and resolves to itself`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)
        service.setOptions(alice, PartyOptions(format = MatchFormat.RANKED_2V2, ranked = true))

        assertNull(service.viewOf(partyId)!!.startBlockedReason)
        assertTrue(service.setQueued(partyId, true))
        assertEquals(listOf(setOf(alice, bob)), service.resolveTeams(partyId)!!.map { it.toSet() })
    }

    @Test
    fun `a member leaving a queued party cancels the search`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)
        service.setOptions(alice, PartyOptions(format = MatchFormat.RANKED_2V2, ranked = true))
        assertTrue(service.setQueued(partyId, true))

        val cancelled = mutableListOf<Triple<UUID, List<UUID>, String>>()
        service.onQueueCancelled { id, members, reason -> cancelled += Triple(id, members, reason) }

        service.leave(bob)

        assertEquals(1, cancelled.size, "the leader's search must not survive losing a member")
        assertEquals(partyId, cancelled.single().first)
        assertFalse(service.viewOf(partyId)!!.queued)
    }

    @Test
    fun `the leader leaving a queued party cancels the search for everyone`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)
        service.setOptions(alice, PartyOptions(format = MatchFormat.RANKED_2V2, ranked = true))
        service.setQueued(partyId, true)

        val cancelled = mutableListOf<Triple<UUID, List<UUID>, String>>()
        service.onQueueCancelled { id, members, reason -> cancelled += Triple(id, members, reason) }

        service.leave(alice)

        val event = cancelled.single()
        assertEquals(partyId, event.first)
        // captured before the disband cleared the roster — both players must be
        // in the event, or one of them never learns the search is over
        assertEquals(setOf(alice, bob), event.second.toSet())
    }

    @Test
    fun `a queued party freezes its roster and settings`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val partyId = partyOf(alice, bob)
        service.setOptions(alice, PartyOptions(format = MatchFormat.RANKED_2V2, ranked = true))
        service.setQueued(partyId, true)

        assertIs<PartyService.Result.Rejected>(service.invite(alice, carol))
        assertIs<PartyService.Result.Rejected>(service.setOptions(alice, PartyOptions()))
        assertIs<PartyService.Result.Rejected>(service.promote(alice, bob))
        assertFalse(service.setQueued(partyId, true), "double-queueing must be refused")
    }

    @Test
    fun `an open-queue format always makes the party one side`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)

        service.setOptions(
            alice,
            // the leader asking for TEAMS in a 2v2 is asking to fight themselves
            PartyOptions(format = MatchFormat.RANKED_2V2, partyMode = PartyMode.TEAMS, ranked = true),
        )
        assertEquals(PartyMode.PARTY_VS_PARTY, service.viewOf(partyId)!!.options.partyMode)
    }

    @Test
    fun `ranked is forced off for any shape that cannot be rated fairly`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)

        // hand-picked teams
        service.setOptions(
            alice,
            PartyOptions(
                format = MatchFormat.RANKED_2V2,
                teamAssignment = TeamAssignment.MANUAL,
                ranked = true,
            ),
        )
        assertFalse(service.viewOf(partyId)!!.options.ranked, "manual teams must not be rated")

        // separate worlds
        service.setOptions(
            alice,
            PartyOptions(format = MatchFormat.RANKED_2V2, sharedWorld = false, ranked = true),
        )
        assertFalse(service.viewOf(partyId)!!.options.ranked, "different worlds must not be rated")

        // an unrated format
        service.setOptions(alice, PartyOptions(format = MatchFormat.CASUAL_2V2, ranked = true))
        assertFalse(service.viewOf(partyId)!!.options.ranked)

        // and the fair shape is allowed through
        service.setOptions(alice, PartyOptions(format = MatchFormat.RANKED_2V2, ranked = true))
        assertTrue(service.viewOf(partyId)!!.options.ranked)
    }

    @Test
    fun `a party free-for-all gives every player their own side`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val partyId = partyOf(alice, bob, carol)
        service.setOptions(alice, PartyOptions(format = MatchFormat.PARTY_FFA))

        val sides = service.resolveTeams(partyId)
        assertNotNull(sides)
        assertEquals(3, sides.size)
        assertTrue(sides.all { it.size == 1 })
        assertEquals(setOf(alice, bob, carol), sides.flatten().toSet())
    }

    @Test
    fun `balanced party teams split the roster evenly`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        val dave = player("Dave")
        roster[alice]!!.rating = 1600
        roster[bob]!!.rating = 1500
        roster[carol]!!.rating = 1000
        roster[dave]!!.rating = 900
        val partyId = partyOf(alice, bob, carol, dave)
        service.setOptions(
            alice,
            PartyOptions(format = MatchFormat.PARTY_TEAMS, teamAssignment = TeamAssignment.BALANCED),
        )

        val sides = service.resolveTeams(partyId)!!
        assertEquals(2, sides.size)
        assertTrue(sides.all { it.size == 2 })
        // snake draft: the strongest and the weakest end up together
        assertTrue(
            sides.any { it.containsAll(listOf(alice, dave)) },
            "balancing should not stack both strong players on one side",
        )
    }

    @Test
    fun `manual teams block the start until every player is assigned`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val partyId = partyOf(alice, bob)
        service.setOptions(
            alice,
            PartyOptions(format = MatchFormat.PARTY_TEAMS, teamAssignment = TeamAssignment.MANUAL),
        )

        assertNotNull(service.viewOf(partyId)!!.startBlockedReason)
        service.setTeam(alice, alice, 0)
        service.setTeam(alice, bob, 1)
        assertNull(service.viewOf(partyId)!!.startBlockedReason)
        assertEquals(listOf(listOf(alice), listOf(bob)), service.resolveTeams(partyId))
    }

    @Test
    fun `two players who each created a party can still invite each other`() {
        // The exact dead end this hit in practice: both players opened the party
        // screen, both pressed create, and from then on every invite between
        // them was refused as "they are already in a party".
        val alice = player("Alice")
        val bob = player("Bob")
        service.create(alice)
        service.create(bob)
        val bobsOwn = service.partyIdOf(bob)!!

        assertIs<PartyService.Result.Ok>(service.invite(alice, bob))
        assertIs<PartyService.Result.Ok>(service.acceptInvite(bob, service.partyIdOf(alice)!!))

        assertEquals(service.partyIdOf(alice), service.partyIdOf(bob))
        assertEquals(2, service.membersOf(service.partyIdOf(alice)!!).size)
        assertNull(service.viewOf(bobsOwn), "his empty lobby should be gone, not left behind")
    }

    @Test
    fun `someone in a real party is not invitable`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        partyOf(bob, carol)

        service.create(alice)
        val result = service.invite(alice, bob)
        assertIs<PartyService.Result.Rejected>(result)
        assertTrue("already in a party" in result.reason, "reason was: ${result.reason}")
    }

    @Test
    fun `accepting is refused while a real party still needs leaving`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")

        // Both invite Bob while he is free, so both invites are valid…
        service.create(alice)
        val alicesParty = service.partyIdOf(alice)!!
        assertIs<PartyService.Result.Ok>(service.invite(alice, bob))
        service.create(carol)
        val carolsParty = service.partyIdOf(carol)!!
        assertIs<PartyService.Result.Ok>(service.invite(carol, bob))

        // …he takes Carol's, which makes hers a real party of two.
        assertIs<PartyService.Result.Ok>(service.acceptInvite(bob, carolsParty))

        // Alice's invite is now stale: honouring it would strand Carol.
        assertIs<PartyService.Result.Rejected>(service.acceptInvite(bob, alicesParty))
        assertEquals(setOf(bob, carol), service.membersOf(carolsParty).toSet())
    }

    @Test
    fun `inviting someone already in your own party is refused`() {
        val alice = player("Alice")
        val bob = player("Bob")
        partyOf(alice, bob)

        val result = service.invite(alice, bob)
        assertIs<PartyService.Result.Rejected>(result)
        assertTrue("your party" in result.reason, "reason was: ${result.reason}")
    }

    @Test
    fun `a player cannot be in two parties`() {
        val alice = player("Alice")
        val bob = player("Bob")
        val carol = player("Carol")
        partyOf(alice, bob)
        service.create(carol)
        val carolsParty = service.partyIdOf(carol)!!

        // refused at the invite, and again at the accept if one somehow existed
        assertIs<PartyService.Result.Rejected>(service.invite(carol, bob))
        assertIs<PartyService.Result.Rejected>(service.acceptInvite(bob, carolsParty))
        assertEquals(service.partyIdOf(alice), service.partyIdOf(bob))
    }

    @Test
    fun `a second socket for the same account replaces the first`() {
        val alice = player("Alice")
        val replaced = mutableListOf<PartyServerMessage>()
        service.connect(alice) { replaced += it }

        assertTrue(
            messages(alice).any { it is PartyServerMessage.Error },
            "the original socket must be told it was superseded",
        )
        assertTrue(replaced.any { it is PartyServerMessage.State })
    }

    @Test
    fun `disconnecting removes the player and clears their presence`() {
        val alice = player("Alice")
        val bob = player("Bob", connect = false)
        service.create(alice)
        val sink: (PartyServerMessage) -> Unit = {}
        service.connect(bob, sink)
        service.invite(alice, bob)
        service.acceptInvite(bob, service.partyIdOf(alice)!!)

        service.disconnect(bob, sink)

        assertNull(service.partyIdOf(bob))
        assertEquals(PresenceState.OFFLINE, presence.stateOf(bob))
    }

    @Test
    fun `a stale socket closing does not knock out the live one`() {
        val alice = player("Alice")
        val stale: (PartyServerMessage) -> Unit = {}
        service.connect(alice, stale)
        val live = mutableListOf<PartyServerMessage>()
        service.connect(alice) { live += it }
        service.create(alice)

        service.disconnect(alice, stale)

        assertNotNull(service.partyIdOf(alice), "the live socket's party must survive")
    }

    @Test
    fun `an unknown or banned player cannot be invited`() {
        val alice = player("Alice")
        val ghost = UUID.randomUUID()
        val bob = player("Bob")
        roster[bob]!!.banned = true

        assertIs<PartyService.Result.Rejected>(service.invite(alice, ghost))
        assertIs<PartyService.Result.Rejected>(service.invite(alice, bob))
    }

    @Test
    fun `you cannot invite yourself`() {
        val alice = player("Alice")
        assertIs<PartyService.Result.Rejected>(service.invite(alice, alice))
    }

    @Test
    fun `accepting an invite to a party that no longer exists is refused`() {
        val alice = player("Alice")
        val bob = player("Bob")
        service.create(alice)
        val partyId = service.partyIdOf(alice)!!
        service.invite(alice, bob)
        service.leave(alice) // disbands

        assertIs<PartyService.Result.Rejected>(service.acceptInvite(bob, partyId))
        assertNull(service.partyIdOf(bob))
    }
}
