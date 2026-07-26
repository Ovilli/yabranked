package dev.yabranked.client

import dev.yabranked.proto.MatchHistoryEntry
import dev.yabranked.proto.MatchTeam
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.QueueServerMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RankedStateTest {

    private fun entry(result: String) = MatchHistoryEntry(
        matchId = "m-$result",
        opponent = PlayerRef(uuid = "u", name = "Opponent"),
        result = result,
        ratingBefore = 1000,
        ratingAfter = 1010,
        durationSeconds = 300,
        completedAt = 1_700_000_000,
    )

    /** RankedState is a process-wide singleton; leave it as we found it. */
    @AfterTest
    fun cleanup() {
        RankedState.queueReconnecting = false
        RankedState.reset()
        RankedState.backend = null
    }

    @Test
    fun `the win streak counts from the newest match`() {
        // the history endpoint returns newest-first, so the streak is the run at
        // the head of the list, not anywhere in it
        assertEquals(3, RankedState.currentWinStreak(listOf("win", "win", "win", "loss", "win").map(::entry)))
    }

    @Test
    fun `a fresh loss ends the streak even with wins behind it`() {
        assertEquals(0, RankedState.currentWinStreak(listOf("loss", "win", "win").map(::entry)))
    }

    @Test
    fun `draws and voids break the streak too`() {
        // only "win" continues it — a void must not silently extend a streak
        assertEquals(1, RankedState.currentWinStreak(listOf("win", "draw", "win").map(::entry)))
        assertEquals(1, RankedState.currentWinStreak(listOf("win", "void", "win").map(::entry)))
    }

    @Test
    fun `no history is a streak of zero`() {
        assertEquals(0, RankedState.currentWinStreak(emptyList()))
    }

    @Test
    fun `a reconnecting socket still counts as queued`() {
        // there is no live socket mid-retry, but the player never asked to
        // leave, so every screen must keep saying "searching"
        RankedState.queueReconnecting = true

        assertTrue(RankedState.isQueued)
    }

    @Test
    fun `reset clears the queue and match state but keeps the session`() {
        RankedState.backend = BackendClient("http://localhost:1", "test")
        RankedState.queueReconnecting = true
        RankedState.queueStatus = "Joining queue…"
        RankedState.activeMatch = matchFound()
        RankedState.lastMatch = matchFound()
        RankedState.matchStartedAt = 42L
        RankedState.lastMatchReported = true
        RankedState.lastRatingChange = -8
        RankedState.statusMessage = "hi"

        RankedState.reset()

        assertTrue(!RankedState.isQueued)
        assertNull(RankedState.queueStatus)
        assertNull(RankedState.activeMatch)
        assertNull(RankedState.lastMatch)
        assertNull(RankedState.matchStartedAt)
        assertNull(RankedState.lastRatingChange)
        assertNull(RankedState.statusMessage)
        assertTrue(!RankedState.lastMatchReported)
        // reset is used for transient UI cleanup, so a still-valid login and the
        // profile behind it deliberately survive it
        assertNotNull(RankedState.backend, "reset must not log the player out")
    }

    private fun matchFound() = QueueServerMessage.MatchFound(
        matchId = "m1",
        team = MatchTeam.TEAM_A,
        opponent = PlayerRef(uuid = "u", name = "Opponent"),
        serverAddress = "host:25565",
    )
}
