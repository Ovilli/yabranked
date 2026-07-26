package dev.yabranked.backend.dev

import dev.yabranked.backend.achievement.AchievementContext
import dev.yabranked.backend.achievement.AchievementDef
import dev.yabranked.backend.store.AchievementStore
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.SeasonStats
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchSettings
import java.time.Instant
import java.util.UUID
import kotlin.math.exp
import kotlin.math.pow
import kotlin.random.Random

/**
 * Dev-only fixture data: a fake but plausible competitive scene so the ranked
 * screens (leaderboard, profiles, match history, elo charts, head-to-head) can
 * be reviewed without running real matches. Enabled with YABRANKED_SEED=1;
 * intended for the in-memory store. Set YABRANKED_SEED_ME=<name> to also seed
 * the profile you log in as under fake auth, so your own history is populated.
 */
object Seeder {

    /** name → country, skill (higher wins more; drives where they settle). */
    private data class Bot(val name: String, val country: String, val skill: Double)

    private val roster = listOf(
        Bot("Feinberg", "us", 1.5),
        Bot("k4yfour", "ca", 1.3),
        Bot("Couriway", "fr", 1.2),
        Bot("Silvernine", "kr", 1.1),
        Bot("Oxidiane", "de", 0.9),
        Bot("J636", "gb", 0.8),
        Bot("Ravlyk", "ua", 0.7),
        Bot("Zylenox", "nl", 0.5),
        Bot("Momoka", "jp", 0.4),
        Bot("Brentilda", "au", 0.3),
        Bot("Priffin", "se", 0.15),
        Bot("Vortu", "pl", 0.0),
        Bot("Halny", "cz", -0.1),
        Bot("Duncan_", "gb", -0.2),
        Bot("Sekoia", "fr", -0.35),
        Bot("mrpog", "us", -0.5),
        Bot("Talwinn", "es", -0.7),
        Bot("Ghostpepper", "br", -0.8),
        Bot("Ninetoes", "no", -0.95),
        Bot("Clamps", "us", -1.1),
        Bot("Bumblewick", "gb", -1.3),
        Bot("Newbie77", "it", -1.6),
    )

    private const val AVG_GAMES_PER_PLAYER = 20
    private const val K = 24
    private const val DRAW_CHANCE = 0.04
    private val SPREAD = java.time.Duration.ofDays(14)

    fun seed(
        players: PlayerStore,
        matches: MatchStore,
        season: Int,
        selfName: String? = null,
        achievements: AchievementStore? = null,
    ) {
        val rng = Random(20260723)
        val now = Instant.now()

        val bots = roster.toMutableList()
        if (selfName != null && roster.none { it.name.equals(selfName, ignoreCase = true) }) {
            bots += Bot(selfName, "us", 0.35)
        }

        val uuidOf = bots.associate { it.name to offlineUuid(it.name) }
        val accountCreated = now.minus(java.time.Duration.ofDays(30))
        bots.forEach {
            players.upsertPlayer(
                PlayerRecord(
                    uuid = uuidOf.getValue(it.name),
                    name = it.name,
                    createdAt = accountCreated,
                    country = it.country,
                )
            )
        }

        // Running per-player ladder state, evolved match by match.
        val rating = bots.associate { it.name to 1000 }.toMutableMap()
        val wins = bots.associate { it.name to 0 }.toMutableMap()
        val losses = bots.associate { it.name to 0 }.toMutableMap()
        val draws = bots.associate { it.name to 0 }.toMutableMap()
        val played = bots.associate { it.name to 0 }.toMutableMap()
        val playtime = bots.associate { it.name to 0L }.toMutableMap()
        val forfeits = bots.associate { it.name to 0 }.toMutableMap()
        val peak = bots.associate { it.name to 1000 }.toMutableMap()

        val totalRounds = bots.size * AVG_GAMES_PER_PLAYER / 2
        val stepSeconds = SPREAD.seconds / totalRounds.coerceAtLeast(1)

        for (round in 0 until totalRounds) {
            val a = bots[rng.nextInt(bots.size)]
            var b = bots[rng.nextInt(bots.size)]
            while (b.name == a.name) b = bots[rng.nextInt(bots.size)]

            val ra = rating.getValue(a.name)
            val rb = rating.getValue(b.name)
            val expA = 1.0 / (1.0 + 10.0.pow((rb - ra) / 400.0))
            // Win probability from the skill gap, independent of current rating so
            // the ladder can still converge toward each bot's true strength.
            val pA = 1.0 / (1.0 + exp(-(a.skill - b.skill) * 1.4))

            val outcome: MatchOutcome
            val scoreA: Double
            when {
                rng.nextDouble() < DRAW_CHANCE -> { outcome = MatchOutcome.DRAW; scoreA = 0.5 }
                rng.nextDouble() < pA -> { outcome = MatchOutcome.TEAM_A_WIN; scoreA = 1.0 }
                else -> { outcome = MatchOutcome.TEAM_B_WIN; scoreA = 0.0 }
            }

            val deltaA = Math.round(K * (scoreA - expA)).toInt()
            val newA = (ra + deltaA).coerceAtLeast(0)
            val newB = (rb - deltaA).coerceAtLeast(0)

            val completedAt = now.minusSeconds((totalRounds - round).toLong() * stepSeconds)
            val duration = (300 + rng.nextInt(600)).toLong()

            // Occasionally the loser forfeited (short game). Draws never forfeit.
            val loser = when (outcome) {
                MatchOutcome.TEAM_A_WIN -> b.name
                MatchOutcome.TEAM_B_WIN -> a.name
                else -> null
            }
            val forfeitedByUuid = if (loser != null && rng.nextDouble() < 0.08) uuidOf.getValue(loser) else null

            // Plausible bingo item counts: winner leads, forfeits end early/low.
            val winnerItems = if (forfeitedByUuid != null) rng.nextInt(1, 4) else rng.nextInt(5, 10)
            val loserItems = if (forfeitedByUuid != null) 0 else rng.nextInt(0, winnerItems)
            val itemsA: Int?
            val itemsB: Int?
            when (outcome) {
                MatchOutcome.TEAM_A_WIN -> { itemsA = winnerItems; itemsB = loserItems }
                MatchOutcome.TEAM_B_WIN -> { itemsA = loserItems; itemsB = winnerItems }
                MatchOutcome.DRAW -> { val t = rng.nextInt(4, 8); itemsA = t; itemsB = t }
                MatchOutcome.VOID -> { itemsA = null; itemsB = null }
            }

            matches.insert(
                MatchRecord(
                    id = UUID.randomUUID(),
                    season = season,
                    format = MatchFormat.LOCKOUT_1V1,
                    settings = MatchSettings(
                        format = MatchFormat.LOCKOUT_1V1,
                        worldSeed = rng.nextLong(),
                        cardSeed = rng.nextLong(),
                        timeLimitSeconds = 1800,
                    ),
                    playerA = uuidOf.getValue(a.name),
                    playerB = uuidOf.getValue(b.name),
                    status = MatchStatus.COMPLETED,
                    serverToken = "seed",
                    serverAddress = null,
                    outcome = outcome,
                    ratingABefore = ra,
                    ratingBBefore = rb,
                    ratingAAfter = newA,
                    ratingBAfter = newB,
                    durationSeconds = duration,
                    teamAScore = itemsA,
                    teamBScore = itemsB,
                    forfeitedBy = forfeitedByUuid,
                    createdAt = completedAt.minusSeconds(duration),
                    completedAt = completedAt,
                )
            )

            rating[a.name] = newA
            rating[b.name] = newB
            peak[a.name] = maxOf(peak.getValue(a.name), newA)
            peak[b.name] = maxOf(peak.getValue(b.name), newB)
            played[a.name] = played.getValue(a.name) + 1
            played[b.name] = played.getValue(b.name) + 1
            playtime[a.name] = playtime.getValue(a.name) + duration
            playtime[b.name] = playtime.getValue(b.name) + duration
            if (loser != null && forfeitedByUuid != null) forfeits[loser] = forfeits.getValue(loser) + 1
            when (outcome) {
                MatchOutcome.DRAW -> { draws[a.name] = draws.getValue(a.name) + 1; draws[b.name] = draws.getValue(b.name) + 1 }
                MatchOutcome.TEAM_A_WIN -> { wins[a.name] = wins.getValue(a.name) + 1; losses[b.name] = losses.getValue(b.name) + 1 }
                MatchOutcome.TEAM_B_WIN -> { wins[b.name] = wins.getValue(b.name) + 1; losses[a.name] = losses.getValue(a.name) + 1 }
                MatchOutcome.VOID -> {}
            }
        }

        bots.forEach {
            val stats = SeasonStats(
                uuid = uuidOf.getValue(it.name),
                season = season,
                rating = rating.getValue(it.name),
                matchesPlayed = played.getValue(it.name),
                wins = wins.getValue(it.name),
                losses = losses.getValue(it.name),
                draws = draws.getValue(it.name),
                playtimeSeconds = playtime.getValue(it.name),
                forfeits = forfeits.getValue(it.name),
                peakRating = peak.getValue(it.name),
            )
            players.upsertStats(stats)

            // Give the fixture players any achievements their totals qualify for,
            // so the profile badge row has something to show without a live match.
            // Streaks aren't tracked in the fixture, so streak milestones stay
            // locked here — that's fine for a visual review.
            if (achievements != null) {
                val context = AchievementContext(
                    stats = players.lifetimeStats(stats.uuid),
                    winStreak = 0,
                    placed = stats.matchesPlayed >= 5,
                )
                for (def in AchievementDef.qualifying(context)) {
                    achievements.award(uuidOf.getValue(it.name), def.id, Instant.now())
                }
            }
        }
    }

    private fun offlineUuid(name: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
}
