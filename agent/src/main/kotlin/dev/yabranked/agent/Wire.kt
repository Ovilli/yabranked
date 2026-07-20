package dev.yabranked.agent

import kotlinx.serialization.Serializable

/**
 * Mirror of dev.yabranked.proto.MatchRules — the proto module is a plain JVM
 * library that cannot be nested into a Fabric mod jar yet (see BackendReporter).
 */
@Serializable
data class MatchRules(
    val lockout: Boolean = true,
    val inventory: Boolean = false,
    val hiddenItems: Boolean = false,
    val consumeItems: Boolean = false,
    val goalType: String = "lines",
    val goalCount: Int = 1,
    val timeLimitMinutes: Int = 90,
    val pvp: Boolean = true,
    val difficulty: List<Int>? = null,
)
