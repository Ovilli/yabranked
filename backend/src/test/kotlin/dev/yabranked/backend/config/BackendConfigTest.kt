package dev.yabranked.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendConfigTest {

    private fun config(vararg env: Pair<String, String>, args: Array<String> = emptyArray()) =
        BackendConfig.fromEnv(args, env.toMap()::get)

    @Test
    fun `booleans read the same way whatever the spelling`() {
        for (yes in listOf("1", "true", "TRUE", "yes", "on")) {
            assertTrue(config("YABRANKED_ORCHESTRATE" to yes).orchestrate, "'$yes' should be true")
        }
        for (no in listOf("0", "false", "FALSE", "no", "off")) {
            assertTrue(!config("YABRANKED_ORCHESTRATE" to no).orchestrate, "'$no' should be false")
        }
    }

    @Test
    fun `ONLINE_MODE=0 now disables online mode instead of enabling it`() {
        // The old parsing was `!= "false"`, so "0" — the obvious way to write it
        // — turned online mode on. This is the behaviour change operators feel.
        assertTrue(!config("YABRANKED_ONLINE_MODE" to "0").onlineMode)
        assertTrue(config().onlineMode, "online mode should still default to on")
    }

    @Test
    fun `a boolean that is neither true nor false is rejected`() {
        val e = assertFailsWith<ConfigException> { config("YABRANKED_SEED" to "maybe") }

        assertTrue("YABRANKED_SEED" in e.message!!, "the message must name the setting: ${e.message}")
    }

    @Test
    fun `blank is treated as unset`() {
        // compose writes an empty string for a variable it has no value for
        assertNull(config("YABRANKED_DATABASE_URL" to "   ").databaseUrl)
        assertTrue(config("YABRANKED_ORCHESTRATE" to "").orchestrate.not())
    }

    @Test
    fun `an out of range port is rejected`() {
        assertFailsWith<ConfigException> { config("YABRANKED_PORT" to "70000") }
        assertFailsWith<ConfigException> { config("YABRANKED_PORT" to "0") }
        assertFailsWith<ConfigException> { config("YABRANKED_PORT" to "http") }
    }

    @Test
    fun `fake auth comes from either the flag or the environment`() {
        assertTrue(config(args = arrayOf("--fake-auth")).fakeAuth)
        assertTrue(config("YABRANKED_FAKE_AUTH" to "1").fakeAuth)
        assertTrue(!config().fakeAuth)
    }

    @Test
    fun `the agent backend url follows host networking unless set explicitly`() {
        // without host networking the container has its own loopback, so
        // "localhost" would point the agent back at itself
        assertEquals(
            "http://host.docker.internal:8080",
            config("YABRANKED_HOST_NETWORK" to "false").backendUrlForAgents,
        )
        assertEquals("http://localhost:8080", config().backendUrlForAgents)
        assertEquals(
            "http://example:9",
            config("YABRANKED_BACKEND_URL_FOR_AGENTS" to "http://example:9").backendUrlForAgents,
        )
    }

    @Test
    fun `container limits default to something and can be removed deliberately`() {
        assertEquals(BackendConfig.DEFAULT_MATCH_MEMORY, config().matchMemory)
        assertEquals(BackendConfig.DEFAULT_MATCH_CPUS, config().matchCpus)

        assertNull(config("YABRANKED_MATCH_MEMORY" to "unlimited").matchMemory)
        assertNull(config("YABRANKED_MATCH_CPUS" to "unlimited").matchCpus)

        assertEquals("512m", config("YABRANKED_MATCH_MEMORY" to "512m").matchMemory)
        assertFailsWith<ConfigException> { config("YABRANKED_MATCH_MEMORY" to "lots") }
        assertFailsWith<ConfigException> { config("YABRANKED_MATCH_CPUS" to "-1") }
    }

    @Test
    fun `a pool size below one is rejected`() {
        assertFailsWith<ConfigException> { config("YABRANKED_DB_POOL_SIZE" to "0") }
    }

    @Test
    fun `season must be a positive integer`() {
        assertEquals(3, config("YABRANKED_SEASON" to "3").season)
        assertFailsWith<ConfigException> { config("YABRANKED_SEASON" to "0") }
        assertFailsWith<ConfigException> { config("YABRANKED_SEASON" to "latest") }
    }

    @Test
    fun `replay limits are unset by default so the policy keeps its own`() {
        val c = config()
        assertNull(c.replayRetentionDays)
        assertNull(c.replaySavedPerPlayer)
        assertNull(c.replaySavedBytesPerPlayer)
        assertNull(c.replayMaxRecordingBytes)
        assertNull(c.matchLogDir)
    }

    @Test
    fun `replay sizes accept a suffix or plain bytes`() {
        assertEquals(
            512L * 1024 * 1024,
            config("YABRANKED_REPLAY_SAVED_BYTES_PER_PLAYER" to "512m").replaySavedBytesPerPlayer,
        )
        assertEquals(
            2L * 1024 * 1024 * 1024,
            config("YABRANKED_REPLAY_SAVED_BYTES_PER_PLAYER" to "2g").replaySavedBytesPerPlayer,
        )
        assertEquals(
            1024L,
            config("YABRANKED_REPLAY_MAX_RECORDING_BYTES" to "1024").replayMaxRecordingBytes,
        )
        // A size that cannot be parsed is refused rather than silently ignored:
        // a replay limit that quietly stayed at its default is how a small disk
        // fills up while its operator believes it is capped.
        assertFailsWith<ConfigException> { config("YABRANKED_REPLAY_MAX_RECORDING_BYTES" to "loads") }
        assertFailsWith<ConfigException> { config("YABRANKED_REPLAY_RETENTION_DAYS" to "0") }
        assertFailsWith<ConfigException> { config("YABRANKED_REPLAY_SAVED_PER_PLAYER" to "-1") }
    }
}
