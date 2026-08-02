package dev.yabranked.agent

import kotlinx.serialization.json.Json

/**
 * The one Json the agent encodes and decodes with.
 *
 * Every module that touches this wire has to configure Json identically, and
 * the agent used to do it in two places that happened to agree. One instance
 * removes the chance of them stopping.
 *
 * **`encodeDefaults` stays false**, which is the library default and is
 * load-bearing rather than incidental: `MatchReplayMeta.format` exists on the
 * shared type but the agent has no business filling it in — the backend knows
 * the format from the match row it is already reading to check the token, and a
 * container repeating it would only create a second answer that could disagree.
 * With defaults omitted the field simply never appears in what the agent sends.
 * `AgentWireFormatTest` asserts that rather than trusting it.
 */
val AgentJson: Json = Json { ignoreUnknownKeys = true }
