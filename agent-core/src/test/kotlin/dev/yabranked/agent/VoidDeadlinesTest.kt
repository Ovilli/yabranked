package dev.yabranked.agent

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The rules that decide whether a match that has not started yet is dead.
 *
 * Every case below is cheap here and expensive anywhere else: reproducing one
 * needs a container, a match and two clients, and the failure it guards is not
 * a crash but a live match being told nobody turned up.
 */
class VoidDeadlinesTest {

    private val anna = AgentConfig.ExpectedPlayer(UUID.randomUUID(), "Anna")
    private val ben = AgentConfig.ExpectedPlayer(UUID.randomUUID(), "Ben")

    private val noShow = 90L
    private val startDeadline = VoidDeadlines.startDeadlineSeconds(noShow)

    private fun evaluate(
        waited: Long,
        missing: List<AgentConfig.ExpectedPlayer> = emptyList(),
        startRequested: Boolean = false,
    ) = VoidDeadlines.evaluate(waited, missing, startRequested, noShow, startDeadline)

    @Test
    fun `nothing happens while there is still time`() {
        assertIs<VoidDecision.KeepWaiting>(evaluate(waited = 0, missing = listOf(ben)))
        assertIs<VoidDecision.KeepWaiting>(evaluate(waited = 89, missing = listOf(ben)))
    }

    @Test
    fun `a missing player is a no-show once the wait is up`() {
        val decision = assertIs<VoidDecision.NoShow>(evaluate(waited = 90, missing = listOf(ben)))
        assertEquals(listOf(ben), decision.missing, "the message names who is missing")
    }

    @Test
    fun `the deadline is inclusive, so a tick landing exactly on it counts`() {
        // The check runs every CHECK_INTERVAL_SECONDS; with `>` instead of `>=`
        // a tick landing exactly on the deadline would wait another interval.
        assertIs<VoidDecision.KeepWaiting>(evaluate(waited = noShow - 1, missing = listOf(ben)))
        assertIs<VoidDecision.NoShow>(evaluate(waited = noShow, missing = listOf(ben)))
    }

    @Test
    fun `everybody present is never a no-show, however long it takes`() {
        // The regression this whole class exists for: both players on their
        // teams, YAB still grinding through a spawn search and a chunk preload,
        // and the match voided out from under them with "your opponent never
        // connected". Presence is the question, not whether the game is running.
        assertIs<VoidDecision.KeepWaiting>(evaluate(waited = 80))
        assertIs<VoidDecision.KeepWaiting>(evaluate(waited = noShow))
        assertIs<VoidDecision.KeepWaiting>(evaluate(waited = startDeadline - 1))
    }

    @Test
    fun `everybody present but no game ends at the longer deadline`() {
        assertIs<VoidDecision.NeverStarted>(evaluate(waited = startDeadline))
    }

    @Test
    fun `once the start is requested a drop-out is not a no-show`() {
        // Everyone was here and on their teams and YAB accepted the start.
        // Whatever a disconnect means at that point, it is not "never connected".
        assertIs<VoidDecision.KeepWaiting>(
            evaluate(waited = noShow, missing = listOf(ben), startRequested = true)
        )
        assertIs<VoidDecision.KeepWaiting>(
            evaluate(waited = startDeadline - 1, missing = listOf(ben), startRequested = true)
        )
        // but it still has to end eventually — the orchestrator's reaper only
        // looks at PENDING matches, so nothing else would ever finish this one
        assertIs<VoidDecision.NeverStarted>(
            evaluate(waited = startDeadline, missing = listOf(ben), startRequested = true)
        )
    }

    @Test
    fun `a match with nobody at all is still a no-show rather than a stuck server`() {
        val decision = assertIs<VoidDecision.NoShow>(evaluate(waited = noShow, missing = listOf(anna, ben)))
        assertEquals(listOf(anna, ben), decision.missing)
    }

    @Test
    fun `the start deadline is three minutes past the no-show wait`() {
        assertEquals(90L + 180L, VoidDeadlines.startDeadlineSeconds(90L))
        assertEquals(180L, VoidDeadlines.START_GRACE_SECONDS)
        // It has to stay strictly longer, or a present-and-slow match would be
        // judged by the no-show rule it is meant to be exempt from.
        for (timeout in listOf(0L, 1L, 30L, 90L, 300L)) {
            assert(VoidDeadlines.startDeadlineSeconds(timeout) > timeout) {
                "start deadline must outlast the no-show wait for timeout=$timeout"
            }
        }
    }

    @Test
    fun `the countdown is only announced while there is countdown left to announce`() {
        assertEquals(listOf(15L, 45L), VoidDeadlines.announcementOffsets(90))
        // "voided in 0s" is not a warning, it is a countdown that already ended
        assertEquals(listOf(15L), VoidDeadlines.announcementOffsets(45))
        assertEquals(emptyList(), VoidDeadlines.announcementOffsets(15))
        assertEquals(emptyList(), VoidDeadlines.announcementOffsets(0))
        for (offset in VoidDeadlines.announcementOffsets(90)) {
            assert(offset < 90) { "announcing at $offset of a 90s wait leaves nothing to count down" }
        }
    }
}
