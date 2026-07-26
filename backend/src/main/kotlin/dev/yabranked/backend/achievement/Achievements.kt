package dev.yabranked.backend.achievement

import dev.yabranked.backend.rating.Tier
import dev.yabranked.backend.store.LifetimeStats

/**
 * Snapshot of everything a milestone can test, assembled at settle time. Keeping
 * the inputs in one value object means an [AchievementDef.test] is a pure
 * predicate — easy to reason about and to unit-test.
 *
 * **Achievements are lifetime, not per-season.** Storage is keyed by account
 * alone, so the predicates have to be too: feeding them the current season's
 * counters meant "Reach Gold" could never be re-earned after season 1 while
 * "Win 10" quietly restarted from zero every rollover — the same catalog
 * behaving two different ways. So [stats] is the player's total across every
 * season, an unlock is permanent, and nothing is ever taken back. The two
 * momentary conditions ([winStreak], [placed]) are still read from the current
 * season, which is the only place they mean anything; having once satisfied
 * them is what the award records.
 */
data class AchievementContext(
    val stats: LifetimeStats,
    /** Current consecutive wins this season, counting the just-settled match. */
    val winStreak: Int,
    /** Whether the player has cleared this season's placement matches. */
    val placed: Boolean,
)

/**
 * The fixed catalog of achievements. Ids are stable strings persisted per
 * player; titles/descriptions are served to the client so it holds no copy of
 * this list. Add new entries at the end — never repurpose an existing id.
 */
enum class AchievementDef(
    val id: String,
    val title: String,
    val description: String,
    val test: (AchievementContext) -> Boolean,
) {
    FIRST_WIN("first_win", "First Blood", "Win your first ranked match.", { it.stats.wins >= 1 }),
    WIN_10("win_10", "Contender", "Win 10 ranked matches.", { it.stats.wins >= 10 }),
    WIN_50("win_50", "Veteran", "Win 50 ranked matches.", { it.stats.wins >= 50 }),
    STREAK_3("streak_3", "On a Roll", "Win 3 ranked matches in a row.", { it.winStreak >= 3 }),
    STREAK_5("streak_5", "Unstoppable", "Win 5 ranked matches in a row.", { it.winStreak >= 5 }),
    STREAK_10("streak_10", "Dominant", "Win 10 ranked matches in a row.", { it.winStreak >= 10 }),
    PLACED("placed", "Ranked Up", "Complete your placement matches.", { it.placed }),
    REACH_GOLD("reach_gold", "Golden", "Reach the Gold tier.", { it.placed && it.stats.peakRating >= Tier.GOLD.floor }),
    REACH_DIAMOND("reach_diamond", "Brilliant", "Reach the Diamond tier.", { it.placed && it.stats.peakRating >= Tier.DIAMOND.floor }),
    REACH_NETHERITE("reach_netherite", "Peak", "Reach the Netherite tier.", { it.placed && it.stats.peakRating >= Tier.NETHERITE.floor }),
    MARATHON("marathon", "Marathon", "Play 10 hours of ranked matches.", { it.stats.playtimeSeconds >= 10 * 3600 }),
    ;

    companion object {
        private val byId = entries.associateBy(AchievementDef::id)
        fun byId(id: String): AchievementDef? = byId[id]

        /** Ids whose predicate holds for [ctx] — the full set the player qualifies for. */
        fun qualifying(ctx: AchievementContext): List<AchievementDef> = entries.filter { it.test(ctx) }
    }
}
