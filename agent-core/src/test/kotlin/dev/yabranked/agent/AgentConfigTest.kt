package dev.yabranked.agent

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `fromEnv` is the whole of the agent's "should I do anything at all?" decision.
 *
 * Getting it wrong is not a crash: a mod that activates when it should not is a
 * mod that starts gating joins and reporting results on somebody's ordinary
 * survival server, and one that stays inert when it should not is a match that
 * never starts and is voided three minutes later. Neither says anything useful
 * in a log.
 */
class AgentConfigTest {

    private val a = UUID.fromString("00000000-0000-0000-0000-00000000000a")
    private val b = UUID.fromString("00000000-0000-0000-0000-00000000000b")
    private val c = UUID.fromString("00000000-0000-0000-0000-00000000000c")
    private val d = UUID.fromString("00000000-0000-0000-0000-00000000000d")

    private fun env(vararg overrides: Pair<String, String?>): Map<String, String> {
        val base = mutableMapOf(
            "YABRANKED_BACKEND_URL" to "http://backend:8080",
            "YABRANKED_MATCH_ID" to "match-1",
            "YABRANKED_SERVER_TOKEN" to "secret",
            "YABRANKED_CARD_SEED" to "42",
            "YABRANKED_PLAYER_A_UUID" to a.toString(),
            "YABRANKED_PLAYER_A_NAME" to "Anna",
            "YABRANKED_PLAYER_B_UUID" to b.toString(),
            "YABRANKED_PLAYER_B_NAME" to "Ben",
        )
        for ((key, value) in overrides) {
            if (value == null) base.remove(key) else base[key] = value
        }
        return base
    }

    @Test
    fun `a complete environment produces a config`() {
        val config = assertNotNull(AgentConfig.fromEnv(env()))
        assertEquals("match-1", config.matchId)
        assertEquals(42L, config.cardSeed)
        assertEquals(listOf(a, b), config.roster.map { it.uuid })
    }

    @Test
    fun `an empty environment leaves the agent inert`() {
        // The case that matters most: this mod on a normal server.
        assertNull(AgentConfig.fromEnv(emptyMap()))
    }

    @Test
    fun `every required variable is genuinely required`() {
        val required = listOf(
            "YABRANKED_BACKEND_URL",
            "YABRANKED_MATCH_ID",
            "YABRANKED_SERVER_TOKEN",
            "YABRANKED_CARD_SEED",
            "YABRANKED_PLAYER_A_UUID",
            "YABRANKED_PLAYER_A_NAME",
            "YABRANKED_PLAYER_B_UUID",
            "YABRANKED_PLAYER_B_NAME",
        )
        for (key in required) {
            assertNull(
                AgentConfig.fromEnv(env(key to null)),
                "$key is missing and the agent still activated",
            )
        }
    }

    @Test
    fun `a malformed uuid or seed is refused rather than defaulted`() {
        // Silently defaulting would produce a match whose roster is not the
        // roster the backend matched, and gate the real players out of it.
        assertNull(AgentConfig.fromEnv(env("YABRANKED_PLAYER_A_UUID" to "not-a-uuid")))
        assertNull(AgentConfig.fromEnv(env("YABRANKED_PLAYER_B_UUID" to "")))
        assertNull(AgentConfig.fromEnv(env("YABRANKED_CARD_SEED" to "twelve")))
    }

    @Test
    fun `the backend url loses only its trailing slash`() {
        assertEquals(
            "http://backend:8080",
            AgentConfig.fromEnv(env("YABRANKED_BACKEND_URL" to "http://backend:8080/"))?.backendUrl,
        )
        assertEquals(
            "http://backend:8080/api",
            AgentConfig.fromEnv(env("YABRANKED_BACKEND_URL" to "http://backend:8080/api"))?.backendUrl,
        )
    }

    @Test
    fun `no rosters means the two-player shape, so an older orchestrator still works`() {
        val config = assertNotNull(AgentConfig.fromEnv(env()))
        assertEquals(listOf(listOf(a), listOf(b)), config.teams.map { side -> side.map { it.uuid } })
        assertEquals(0, config.sideOf(a))
        assertEquals(1, config.sideOf(b))
        assertEquals(listOf(b), config.opponentsOf(a).map { it.uuid })
        assertTrue(config.teammatesOf(a).isEmpty())
    }

    @Test
    fun `rosters are used when the backend sends them`() {
        val teams = """
            [[{"uuid":"$a","name":"Anna"},{"uuid":"$c","name":"Cara"}],
             [{"uuid":"$b","name":"Ben"},{"uuid":"$d","name":"Dan"}]]
        """.trimIndent()
        val config = assertNotNull(AgentConfig.fromEnv(env("YABRANKED_TEAMS" to teams)))

        assertEquals(listOf(listOf(a, c), listOf(b, d)), config.teams.map { side -> side.map { it.uuid } })
        assertEquals(listOf(c), config.teammatesOf(a).map { it.uuid })
        assertEquals(listOf(b, d), config.opponentsOf(a).map { it.uuid })
        assertNull(config.sideOf(UUID.randomUUID()), "a stranger belongs to no side")
    }

    @Test
    fun `an unusable roster falls back to the pair rather than gating everyone out`() {
        // A roster that cannot be parsed is worse than no roster: the gate would
        // admit nobody and the match would be voided for a no-show that was the
        // agent's fault. playerA/playerB are always present, so fall back to them.
        val cases = mapOf(
            "not json" to "{{{",
            "one side" to """[[{"uuid":"$a","name":"Anna"}]]""",
            "empty side" to """[[{"uuid":"$a","name":"Anna"}],[]]""",
            "bad uuid in a side" to """[[{"uuid":"nope","name":"Anna"}],[{"uuid":"$b","name":"Ben"}]]""",
        )
        for ((why, raw) in cases) {
            val config = assertNotNull(AgentConfig.fromEnv(env("YABRANKED_TEAMS" to raw)), why)
            assertEquals(
                listOf(listOf(a), listOf(b)),
                config.teams.map { side -> side.map { it.uuid } },
                "$why should have fallen back to the pair",
            )
        }
    }

    @Test
    fun `timeouts default to the documented values and are overridable`() {
        val defaults = assertNotNull(AgentConfig.fromEnv(env()))
        assertEquals(90L, defaults.noShowTimeoutSeconds, "the no-show wait is 90s, not the old 300s")
        assertEquals(60L, defaults.replayCheckpointSeconds)

        val tuned = assertNotNull(
            AgentConfig.fromEnv(
                env(
                    "YABRANKED_NO_SHOW_TIMEOUT_SECONDS" to "30",
                    "YABRANKED_REPLAY_CHECKPOINT_SECONDS" to "0",
                )
            )
        )
        assertEquals(30L, tuned.noShowTimeoutSeconds)
        assertEquals(0L, tuned.replayCheckpointSeconds, "0 disables checkpointing rather than meaning 'default'")
    }

    @Test
    fun `a negative checkpoint interval is clamped, not trusted`() {
        val config = assertNotNull(AgentConfig.fromEnv(env("YABRANKED_REPLAY_CHECKPOINT_SECONDS" to "-5")))
        assertEquals(0L, config.replayCheckpointSeconds)
    }

    @Test
    fun `an unparseable timeout falls back to the default instead of aborting the match`() {
        val config = assertNotNull(AgentConfig.fromEnv(env("YABRANKED_NO_SHOW_TIMEOUT_SECONDS" to "soon")))
        assertEquals(90L, config.noShowTimeoutSeconds)
    }

    @Test
    fun `team names are stable for the first two sides and never run out`() {
        val config = assertNotNull(AgentConfig.fromEnv(env()))
        assertEquals("red", config.teamNameOf(0), "side 0 has always been red; changing it changes every match")
        assertEquals("blue", config.teamNameOf(1))
        assertEquals("team99", config.teamNameOf(98), "a side past the palette still needs a name")
    }
}
