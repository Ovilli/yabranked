package dev.yabranked.agent

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchReplayMeta
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.ReplayEvent
import dev.yabranked.proto.ReplayEventType
import dev.yabranked.proto.ReplayStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the agent actually puts on the wire.
 *
 * The agent used to declare its own `WireResultReport`, `WireOutcome` and a
 * dozen `WireReplay*` types that mirrored `:proto`'s field for field, and it now
 * uses `:proto`'s directly. The backend has always decoded these with proto's
 * serializers, so the mirrors were only ever a second opinion about a shape
 * somebody else owned — but swapping them is still a change to the settle path,
 * and "the fields look the same" is not the same claim as "the JSON is the
 * same".
 *
 * These assert the JSON as literals. Every one of them would have passed before
 * the swap too, which is the point.
 */
class AgentWireFormatTest {

    @Test
    fun `a result report encodes exactly as it always did`() {
        val json = AgentJson.encodeToString(
            MatchResultReport.serializer(),
            MatchResultReport(
                matchId = "11111111-2222-3333-4444-555555555555",
                outcome = MatchOutcome.TEAM_A_WIN,
                durationSeconds = 700,
                teamAScore = 13,
                teamBScore = 8,
            ),
        )
        assertEquals(
            """{"matchId":"11111111-2222-3333-4444-555555555555","outcome":"team_a",""" +
                """"durationSeconds":700,"teamAScore":13,"teamBScore":8}""",
            json,
        )
    }

    @Test
    fun `the outcome wire names are the ones the backend matches on`() {
        // These are what MatchService.settle switches on. A rename here is a
        // match that never settles, reported as "the result never arrived".
        val names = MatchOutcome.entries.associateWith {
            AgentJson.encodeToString(MatchOutcome.serializer(), it).trim('"')
        }
        assertEquals(
            mapOf(
                MatchOutcome.TEAM_A_WIN to "team_a",
                MatchOutcome.TEAM_B_WIN to "team_b",
                MatchOutcome.DRAW to "draw",
                MatchOutcome.VOID to "void",
            ),
            names,
        )
    }

    @Test
    fun `a forfeit and a multi-side result carry their extra fields`() {
        val json = AgentJson.encodeToString(
            MatchResultReport.serializer(),
            MatchResultReport(
                matchId = "m", outcome = MatchOutcome.TEAM_B_WIN, durationSeconds = 1,
                teamAScore = 0, teamBScore = 1,
                forfeitedBy = "aaaaaaaa-0000-0000-0000-000000000000",
                winningTeam = 2, teamScores = listOf(3, 4, 9),
            ),
        )
        assertTrue(json.contains(""""forfeitedBy":"aaaaaaaa-0000-0000-0000-000000000000""""), json)
        assertTrue(json.contains(""""winningTeam":2"""), json)
        assertTrue(json.contains(""""teamScores":[3,4,9]"""), json)
    }

    /**
     * The one field where the shared type and the agent genuinely disagree.
     *
     * `MatchReplayMeta.format` exists on proto's type because the client's
     * replay library wants it; the agent has no business filling it in. The
     * backend knows the format from the match row it is already reading to check
     * the server token, and a container repeating it would only create a second
     * answer that could disagree — `ReplayApi.putMeta` stores the agent's body
     * **verbatim**, so a wrong value here would be persisted and served back.
     *
     * Omitting it is not a special case in the agent, it falls out of
     * `encodeDefaults` being false. That is exactly why it is asserted: it is a
     * property of the Json configuration, and nothing about the field itself
     * would complain if that configuration changed.
     */
    @Test
    fun `the replay index does not claim to know the match format`() {
        val json = AgentJson.encodeToString(
            MatchReplayMeta.serializer(),
            MatchReplayMeta(matchId = "m", startedAt = 100, durationSeconds = 60),
        )
        assertFalse(json.contains("format"), "the agent must not send a format it is guessing: $json")
        assertEquals("""{"matchId":"m","startedAt":100,"durationSeconds":60}""", json)
    }

    @Test
    fun `turning encodeDefaults on is what would break that, and it is off`() {
        // Stated as a test rather than a comment, since the failure it prevents
        // is silent: every replay index would start carrying format=lockout_1v1,
        // including every 2v2, 3v3 and 4v4.
        val leaky = kotlinx.serialization.json.Json { encodeDefaults = true }
        assertTrue(
            leaky.encodeToString(MatchReplayMeta.serializer(), MatchReplayMeta(matchId = "m"))
                .contains(""""format":"lockout_1v1""""),
            "if this stops being true the guard above is testing nothing",
        )
        assertFalse(
            AgentJson.encodeToString(MatchReplayMeta.serializer(), MatchReplayMeta(matchId = "m"))
                .contains("format"),
        )
        assertEquals(MatchFormat.LOCKOUT_1V1, MatchReplayMeta(matchId = "m").format)
    }

    @Test
    fun `a populated replay index round-trips`() {
        val meta = MatchReplayMeta(
            matchId = "m",
            startedAt = 1_700_000_000,
            durationSeconds = 900,
            recordedFrom = 1_699_999_990_000,
            gameStartMillis = 10_000,
            events = listOf(
                ReplayEvent(atSeconds = 0, type = ReplayEventType.GAME_START, detail = "Match started"),
                ReplayEvent(
                    atSeconds = 42,
                    type = ReplayEventType.CLAIM,
                    player = PlayerRef("11111111-1111-1111-1111-111111111111", "Anna"),
                    team = 0,
                    cell = 7,
                ),
            ),
            streams = listOf(
                ReplayStreamInfo(
                    index = 0,
                    player = PlayerRef("11111111-1111-1111-1111-111111111111", "Anna"),
                    sizeBytes = 1234,
                    packetCount = 56,
                    endMillis = 900_000,
                )
            ),
            gameVersion = "26.2",
            protocolVersion = 800,
        )

        val encoded = AgentJson.encodeToString(MatchReplayMeta.serializer(), meta)
        assertEquals(meta, AgentJson.decodeFromString(MatchReplayMeta.serializer(), encoded))
        // The event type names are unannotated in :proto and were @SerialName'd
        // in the agent's copy; kotlinx defaults an entry's serial name to the
        // entry name, so both produce this. That equivalence is the reason the
        // swap did not change the wire.
        assertTrue(encoded.contains(""""type":"GAME_START""""), encoded)
        assertTrue(encoded.contains(""""type":"CLAIM""""), encoded)
    }

    @Test
    fun `the index still declares format version 2`() {
        // version is what the client checks before trying to play a recording.
        assertEquals(2, MatchReplayMeta(matchId = "m").version)
        assertTrue(
            AgentJson.encodeToString(MatchReplayMeta.serializer(), MatchReplayMeta(matchId = "m", version = 2))
                .let { !it.contains("version") },
            "2 is the default, so it is omitted — the client defaults to it too",
        )
    }
}
