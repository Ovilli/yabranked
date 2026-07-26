package dev.yabranked.client.ui

import dev.yabranked.client.RankedState
import dev.yabranked.proto.MatchTeam
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.QueueServerMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class QueueBadgeTest {

    /** RankedState is a process-wide singleton; leave it as we found it. */
    @AfterTest
    fun cleanup() {
        RankedState.queueReconnecting = false
        RankedState.activeMatch = null
    }

    @Test
    fun `the badge is hidden when not queueing`() {
        assertTrue(!QueueBadge.isVisible())
    }

    @Test
    fun `the badge shows while queueing, including mid-reconnect`() {
        // the badge exists so a player can queue from anywhere in the game; a
        // dropped socket being retried is still queueing
        RankedState.queueReconnecting = true

        assertTrue(QueueBadge.isVisible())
    }

    @Test
    fun `the badge goes away once a match is found`() {
        // MatchFoundScreen takes over here — a "searching" badge over it would
        // say the opposite of what is happening
        RankedState.queueReconnecting = true
        RankedState.activeMatch = QueueServerMessage.MatchFound(
            matchId = "m1",
            team = MatchTeam.TEAM_A,
            opponent = PlayerRef(uuid = "u", name = "Opponent"),
            serverAddress = "host:25565",
        )

        assertTrue(!QueueBadge.isVisible())
    }
}
