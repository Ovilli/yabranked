package dev.yabranked.backend.match

import dev.yabranked.backend.achievement.AchievementContext
import dev.yabranked.backend.achievement.AchievementDef
import dev.yabranked.backend.queue.QueueMatch
import dev.yabranked.backend.rating.EloTeamRatingSystem
import dev.yabranked.backend.rating.RatingState
import dev.yabranked.backend.rating.RatingSystem
import dev.yabranked.backend.rating.TeamRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.AchievementStore
import dev.yabranked.backend.store.InMemoryAchievementStore
import dev.yabranked.backend.store.InMemoryModeStatsStore
import dev.yabranked.backend.store.LockingTransactionRunner
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStatus
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.ModeStatsRecord
import dev.yabranked.backend.store.ModeStatsStore
import dev.yabranked.backend.store.PlayerRecord
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.SeasonStats
import dev.yabranked.backend.store.TransactionRunner
import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import dev.yabranked.proto.MatchResultReport
import dev.yabranked.proto.MatchSettings
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

/**
 * Creates match records from queue matches and settles reported results.
 * Provisioning the actual game server is the orchestrator's job — it
 * subscribes via [onMatchCreated] / [onMatchSettled].
 */
class MatchService(
    private val players: PlayerStore,
    private val matches: MatchStore,
    private val rating: RatingSystem,
    private val seasons: SeasonService,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
    private val achievements: AchievementStore = InMemoryAchievementStore(),
    /**
     * Makes the settle path atomic. Every write in [settle] — both players'
     * stats, the match row and the achievement unlocks — commits together or
     * not at all, and concurrent settles touching the same player serialize
     * instead of clobbering each other's rating.
     */
    private val transactions: TransactionRunner = LockingTransactionRunner(),
    /**
     * Per-mode counters and per-mode ladders. Every settled match writes a row
     * here — including casual ones, which is what makes the profile's playtime
     * breakdown per mode possible — while [players] keeps carrying the solo
     * ranked ladder exactly as it always did.
     */
    private val modeStats: ModeStatsStore = InMemoryModeStatsStore(),
    /** Rating engine for anything with more than one player a side. */
    private val teamRating: TeamRatingSystem = EloTeamRatingSystem(),
) {
    private val log = org.slf4j.LoggerFactory.getLogger("match")

    private val listeners = mutableListOf<(MatchRecord) -> Unit>()
    private val settledListeners = mutableListOf<(MatchRecord) -> Unit>()

    fun onMatchCreated(listener: (MatchRecord) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    fun onMatchSettled(listener: (MatchRecord) -> Unit) {
        synchronized(settledListeners) { settledListeners += listener }
    }

    /**
     * Notify [subscribers], one failure at a time.
     *
     * Every listener here does slow, fallible I/O — `docker run`, `docker rm`,
     * a metrics write — on a record that is already committed. Letting one throw
     * out of here meant the caller was told the write had failed when it had
     * not: an agent reporting a result got a 500 for a match that *had* settled
     * and retried into a 409, and every listener after the failing one — which
     * is what tears the container down — never ran at all.
     *
     * The snapshot is taken under the lock because subscribers are registered
     * from the server module while the matchmaking tick is already running.
     */
    private fun fire(
        subscribers: MutableList<(MatchRecord) -> Unit>,
        what: String,
        record: MatchRecord,
    ) {
        val snapshot = synchronized(subscribers) { subscribers.toList() }
        for (listener in snapshot) {
            runCatching { listener(record) }
                .onFailure { log.error("$what listener failed for match ${record.id}", it) }
        }
    }

    fun getOrCreatePlayer(uuid: UUID, name: String): PlayerRecord {
        val existing = players.getPlayer(uuid)
        val record = when {
            existing == null -> PlayerRecord(uuid = uuid, name = name, createdAt = clock.instant())
            existing.name != name -> existing.copy(name = name) // MC accounts can rename
            else -> existing
        }
        if (record !== existing) players.upsertPlayer(record)
        return record
    }

    /**
     * Current-season stats, defaulting to the initial rating for a player with
     * no row yet. Purely a read: persisting that placeholder here meant every
     * profile view, leaderboard render and queue join wrote to the database.
     * Only [settle] creates the row.
     */
    fun statsFor(uuid: UUID): SeasonStats {
        val season = seasons.currentSeason
        return players.getStats(uuid, season) ?: freshStats(uuid, season)
    }

    /**
     * The rating matchmaking should use for [format].
     *
     * Solo formats read the season ladder; every team format has its own,
     * because being good at 1v1 says very little about 3v3 and letting one
     * seed the other would put a solo top-ten player into their first team
     * match against the wrong opponents.
     */
    fun ratingFor(uuid: UUID, format: MatchFormat): Int =
        if (format.teamSize <= 1) statsFor(uuid).rating
        else modeStatsFor(uuid, format).rating

    /** This player's row for one mode, defaulted for a mode never played. */
    fun modeStatsFor(uuid: UUID, format: MatchFormat): ModeStatsRecord {
        val season = seasons.currentSeason
        return modeStats.get(uuid, season, format) ?: freshModeStats(uuid, season, format)
    }

    /** Every mode this player has played this season. */
    fun modesFor(uuid: UUID): List<ModeStatsRecord> = modeStats.allFor(uuid, seasons.currentSeason)

    /** Read access for the per-mode leaderboards. */
    val modeLadder: ModeStatsStore get() = modeStats

    /** Matches owed before a mode's rating is treated as real. */
    val teamPlacementMatches: Int get() = teamRating.placementMatches

    /**
     * Matches before a player is ranked. Read from the rating system rather
     * than kept alongside it: the two used to declare 5 independently, so
     * drifting one would have had the client counting down placements the
     * K-factor had already stopped applying.
     */
    val placementMatches: Int get() = rating.placementMatches

    /** The ladder's rating rules, for the season rollover and the decay sweep. */
    val ratingSystem: RatingSystem get() = rating

    /**
     * The store's transaction boundary. Exposed so the routes that
     * read-modify-write a row of their own — the profile editor and the ban
     * endpoints, which all rewrite a whole [PlayerRecord] — share *this*
     * boundary rather than inventing a second one that guards nothing.
     */
    val transactionRunner: TransactionRunner get() = transactions

    fun placementMatchesRemaining(stats: SeasonStats): Int =
        (placementMatches - stats.matchesPlayed).coerceAtLeast(0)

    /** Orchestrator: record where the provisioned server for this match lives. */
    fun setServerAddress(matchId: UUID, address: String) {
        transactions.transaction {
            val match = matches.getForUpdate(matchId) ?: error("unknown match $matchId")
            matches.update(match.copy(serverAddress = address))
        }
    }

    sealed interface ReadyResult {
        data object Ok : ReadyResult
        data object UnknownMatch : ReadyResult
        data object BadToken : ReadyResult
    }

    /** Agent: the match server is configured and waiting for its players. */
    fun markReady(matchId: String, serverToken: String): ReadyResult {
        val id = runCatching { UUID.fromString(matchId) }.getOrNull()
            ?: return ReadyResult.UnknownMatch
        return transactions.transaction {
            val match = matches.getForUpdate(id) ?: return@transaction ReadyResult.UnknownMatch
            if (!java.security.MessageDigest.isEqual(
                    match.serverToken.toByteArray(),
                    serverToken.toByteArray()
                )
            ) return@transaction ReadyResult.BadToken

            if (match.status == MatchStatus.PENDING) {
                matches.update(match.copy(status = MatchStatus.ACTIVE))
            }
            ReadyResult.Ok
        }
    }

    /**
     * Void a match without a server token — internal use only (e.g. the
     * orchestrator reaping a match whose server never became ready, or startup
     * reconciliation clearing matches orphaned by a restart).
     */
    fun voidMatch(matchId: UUID) {
        val voided = transactions.transaction {
            val match = matches.getForUpdate(matchId) ?: return@transaction null
            if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.VOIDED) {
                return@transaction null
            }
            match.copy(
                status = MatchStatus.VOIDED,
                outcome = MatchOutcome.VOID,
                completedAt = clock.instant(),
            ).also(matches::update)
        } ?: return
        // listeners (container teardown) run outside the transaction: they do
        // slow I/O and must not hold row locks or be rolled back with it.
        fire(settledListeners, "match-settled", voided)
    }

    fun createMatch(queueMatch: QueueMatch, format: MatchFormat): MatchRecord {
        val record = transactions.transaction { buildMatch(queueMatch, format) }
        fire(listeners, "match-created", record)
        return record
    }

    /**
     * What the caller decided about a team or party match before it existed.
     * [ranked] is passed in rather than read off [format] because a party can
     * play a rated format unrated (and the party layer has already refused the
     * reverse — an unrateable shape cannot arrive here with `ranked = true`).
     */
    data class TeamMatchRequest(
        val format: MatchFormat,
        /** Side-ordered rosters. At least two sides, none empty. */
        val teams: List<List<UUID>>,
        val sharedWorld: Boolean = true,
        val sharedSeed: Boolean = true,
        val ranked: Boolean = format.ranked,
        val partyId: UUID? = null,
    )

    /**
     * Create a match for two or more sides. The 1v1 path stays on
     * [createMatch]; this is what parties and the XvX queue use.
     */
    fun createTeamMatch(request: TeamMatchRequest): MatchRecord {
        require(request.teams.size >= 2) { "a match needs at least two sides" }
        require(request.teams.all { it.isNotEmpty() }) { "a side cannot be empty" }
        val everyone = request.teams.flatten()
        require(everyone.size == everyone.distinct().size) { "a player cannot be on two sides" }

        val record = transactions.transaction { buildTeamMatch(request) }
        fire(listeners, "match-created", record)
        return record
    }

    private fun buildTeamMatch(request: TeamMatchRequest): MatchRecord {
        val format = request.format
        val sides = request.teams
        val ratings = sides.map { side ->
            side.map { ratingFor(it, format) }.average().toInt()
        }

        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        // Per-side seeds only exist when the leader asked the sides apart. They
        // are still generated here rather than by the agent so the record is
        // the whole truth about what was played.
        val worldSeed = random.nextLong()
        val cardSeed = random.nextLong()

        val record = MatchRecord(
            id = UUID.randomUUID(),
            season = seasons.currentSeason,
            // An unrated run of a rated format is recorded as the format it was;
            // `ranked` on the settle path is what decides whether it counts.
            format = format,
            settings = MatchSettings(
                format = format,
                worldSeed = worldSeed,
                cardSeed = cardSeed,
                timeLimitSeconds = format.rules.timeLimitMinutes * 60L,
                sharedWorld = request.sharedWorld,
                sharedSeed = request.sharedSeed,
                perTeamWorldSeeds = if (request.sharedWorld) emptyList() else sides.map { random.nextLong() },
                perTeamCardSeeds = if (request.sharedSeed) emptyList() else sides.map { random.nextLong() },
                teamCount = sides.size,
            ),
            playerA = sides[0].first(),
            playerB = sides[1].first(),
            status = MatchStatus.PENDING,
            serverToken = token,
            outcome = null,
            ratingABefore = ratings[0],
            ratingBBefore = ratings[1],
            ratingAAfter = null,
            ratingBAfter = null,
            createdAt = clock.instant(),
            completedAt = null,
            teams = sides,
            partyId = request.partyId,
            rated = request.ranked && format.ranked,
        )
        matches.insert(record)
        return record
    }

    private fun buildMatch(queueMatch: QueueMatch, format: MatchFormat): MatchRecord {
        val statsA = statsFor(queueMatch.playerA.uuid)
        val statsB = statsFor(queueMatch.playerB.uuid)

        val token = ByteArray(32).also(random::nextBytes)
            .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

        val record = MatchRecord(
            id = UUID.randomUUID(),
            season = seasons.currentSeason,
            format = format,
            settings = MatchSettings(
                format = format,
                worldSeed = random.nextLong(),
                cardSeed = random.nextLong(),
                timeLimitSeconds = format.rules.timeLimitMinutes * 60L,
            ),
            playerA = statsA.uuid,
            playerB = statsB.uuid,
            status = MatchStatus.PENDING,
            serverToken = token,
            outcome = null,
            ratingABefore = statsA.rating,
            ratingBBefore = statsB.rating,
            ratingAAfter = null,
            ratingBAfter = null,
            createdAt = clock.instant(),
            completedAt = null,
        )
        matches.insert(record)
        return record
    }

    sealed interface SettleResult {
        data class Settled(val match: MatchRecord) : SettleResult
        data object UnknownMatch : SettleResult
        data object BadToken : SettleResult
        data object AlreadySettled : SettleResult

        /** Someone tried to concede a match they are not playing in. */
        data object NotAParticipant : SettleResult

        /** The agent reported something impossible; nothing was written. */
        data class InvalidReport(val reason: String) : SettleResult
    }

    /**
     * A player conceding from their own client, rather than through the match
     * server's `/forfeit` command.
     *
     * The command path only exists while they are connected to the container,
     * which is exactly when they are least likely to need it: a player whose
     * game crashed, or who backed out to the menus, has no way to reach it and
     * used to leave their opponent waiting out the abandon timer for a match
     * that was already over.
     *
     * Authenticated by participation, not by the per-match server token. That
     * is safe because the only thing this can express is "I lose": the caller's
     * own side is always the one that concedes, so there is nothing here a
     * malicious client could use against anybody else.
     */
    fun forfeit(matchId: UUID, player: UUID): SettleResult {
        val result = transactions.transaction {
            val match = matches.getForUpdate(matchId) ?: return@transaction SettleResult.UnknownMatch
            val side = match.sideOf(player) ?: return@transaction SettleResult.NotAParticipant
            if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.VOIDED) {
                return@transaction SettleResult.AlreadySettled
            }
            val winner = match.rosters.indices.firstOrNull { it != side }
                ?: return@transaction SettleResult.InvalidReport("no other side to award the win to")

            // Clamped rather than trusted: the created-at clock is the backend's,
            // but a match record left behind by a crash can be arbitrarily old
            // and `rejectionReason` would refuse the report outright.
            val elapsed = java.time.Duration.between(match.createdAt, clock.instant()).seconds
                .coerceIn(0, match.settings.timeLimitSeconds.coerceAtLeast(0) + MAX_DURATION_OVERRUN_SECONDS)

            val report = MatchResultReport(
                matchId = matchId.toString(),
                outcome = if (winner == 0) MatchOutcome.TEAM_A_WIN else MatchOutcome.TEAM_B_WIN,
                durationSeconds = elapsed,
                // The scores at the moment of conceding are whatever the agent
                // last wrote; a match nobody has reported on yet has none.
                teamAScore = match.teamAScore ?: 0,
                teamBScore = match.teamBScore ?: 0,
                // Only meaningful above two sides; below it the outcome says it
                // already, and `rejectionReason` insists the two agree.
                winningTeam = winner.takeIf { match.rosters.size > 2 },
                forfeitedBy = player.toString(),
            )
            // The token is the match's own: this call has already proved the
            // caller is in the match, which is the check that matters here.
            settleLocked(matchId, report, match.serverToken)
        }
        // Same contract as settle(): teardown does container I/O, so it runs
        // outside the transaction.
        if (result is SettleResult.Settled) fire(settledListeners, "match-settled", result.match)
        return result
    }

    /**
     * Apply a result reported by the match server. Idempotence: a second
     * report for the same match is rejected with [SettleResult.AlreadySettled].
     */
    fun settle(report: MatchResultReport, serverToken: String): SettleResult {
        val id = runCatching { UUID.fromString(report.matchId) }.getOrNull()
            ?: return SettleResult.UnknownMatch
        val result = transactions.transaction { settleLocked(id, report, serverToken) }
        // teardown listeners do container I/O — keep them out of the transaction
        if (result is SettleResult.Settled) fire(settledListeners, "match-settled", result.match)
        return result
    }

    private fun settleLocked(id: UUID, report: MatchResultReport, serverToken: String): SettleResult {
        val match = matches.getForUpdate(id) ?: return SettleResult.UnknownMatch
        if (!java.security.MessageDigest.isEqual(
                match.serverToken.toByteArray(),
                serverToken.toByteArray()
            )
        ) return SettleResult.BadToken
        if (match.status == MatchStatus.COMPLETED || match.status == MatchStatus.VOIDED)
            return SettleResult.AlreadySettled
        rejectionReason(match, report)?.let { return SettleResult.InvalidReport(it) }

        val rosters = match.rosters
        val winner = winningSideOf(report)
        val voided = report.outcome == MatchOutcome.VOID
        /** Whether ratings move at all: an unrated run of a rated format does not. */
        val counts = !voided && match.rated && match.format.ranked
        /** The classic 1v1 shape, which owns the season ladder. */
        val solo = rosters.size == 2 && rosters.all { it.size == 1 }

        val forfeitedBy = report.forfeitedBy?.let { runCatching { UUID.fromString(it) }.getOrNull() }

        // Playing resets the inactivity clock and clears the decay watermark, so
        // a returning player starts a fresh grace period rather than being
        // billed for the idle time they just ended.
        val playedAt = clock.instant()

        // Lock every row this settle touches in ascending uuid order, season
        // rows before mode rows: two matches sharing a player would otherwise be
        // free to grab them in opposite orders and deadlock.
        val roster = match.participants.sorted()
        val seasonRows = if (solo) {
            roster.associateWith { uuid ->
                players.getStatsForUpdate(uuid, match.season) ?: freshStats(uuid, match.season)
            }
        } else emptyMap()
        val modeRows = roster.associateWith { uuid ->
            modeStats.getForUpdate(uuid, match.season, match.format)
                ?: freshModeStats(uuid, match.season, match.format)
        }

        // --- Season ladder (solo only) ---
        // A team match is rated on its own mode's ladder, never on this one:
        // folding 3v3 results into the 1v1 rating is exactly the mixing the
        // per-mode ladders exist to prevent.
        var ratingAAfter = match.ratingABefore
        var ratingBAfter = match.ratingBBefore
        if (solo) {
            val statsA = seasonRows.getValue(match.playerA)
            val statsB = seasonRows.getValue(match.playerB)
            val update = rating.update(
                playerA = RatingState(statsA.rating, statsA.matchesPlayed),
                playerB = RatingState(statsB.rating, statsB.matchesPlayed),
                outcome = report.outcome,
            )
            // The after-ratings are only what the ladder actually did. Writing
            // the hypothetical ones for a match that never touched it is what
            // made the result screen of a casual game show an MMR swing next to
            // an unchanged rating.
            if (counts) {
                ratingAAfter = update.playerA.rating
                ratingBAfter = update.playerB.rating
            }

            // casual formats record the match but never touch the ladder
            if (counts) {
                players.upsertStats(
                    statsA.copy(
                        lastPlayedAt = playedAt,
                        decayedThrough = null,
                        rating = update.playerA.rating,
                        matchesPlayed = update.playerA.matchesPlayed,
                        wins = statsA.wins + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                        losses = statsA.losses + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                        draws = statsA.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
                        playtimeSeconds = statsA.playtimeSeconds + report.durationSeconds,
                        forfeits = statsA.forfeits + if (forfeitedBy == match.playerA) 1 else 0,
                        peakRating = maxOf(statsA.peakRating, update.playerA.rating),
                    )
                )
                players.upsertStats(
                    statsB.copy(
                        lastPlayedAt = playedAt,
                        decayedThrough = null,
                        rating = update.playerB.rating,
                        matchesPlayed = update.playerB.matchesPlayed,
                        wins = statsB.wins + if (report.outcome == MatchOutcome.TEAM_B_WIN) 1 else 0,
                        losses = statsB.losses + if (report.outcome == MatchOutcome.TEAM_A_WIN) 1 else 0,
                        draws = statsB.draws + if (report.outcome == MatchOutcome.DRAW) 1 else 0,
                        playtimeSeconds = statsB.playtimeSeconds + report.durationSeconds,
                        forfeits = statsB.forfeits + if (forfeitedBy == match.playerB) 1 else 0,
                        peakRating = maxOf(statsB.peakRating, update.playerB.rating),
                    )
                )
            }
        }

        // --- Per-mode counters and ladders ---
        // Written for *every* participant of *every* decided match, casual
        // included: the profile's per-mode playtime breakdown is only possible
        // if unrated modes are counted too. Only `rating` is gated on `counts`.
        if (!voided) {
            val teamUpdate = if (!solo && counts) {
                teamRating.update(
                    sides = rosters.map { side ->
                        side.map { uuid ->
                            val row = modeRows.getValue(uuid)
                            RatingState(row.rating, row.matchesPlayed)
                        }
                    },
                    winner = winner,
                )
            } else null

            rosters.forEachIndexed { sideIndex, side ->
                side.forEachIndexed { seat, uuid ->
                    val row = modeRows.getValue(uuid)
                    val won = winner == sideIndex
                    val lost = winner != null && winner != sideIndex
                    val newRating = when {
                        teamUpdate != null -> teamUpdate[sideIndex][seat].rating
                        // a solo mode's ladder mirrors the season ladder, so the
                        // per-mode leaderboard for 1v1 agrees with the main one
                        solo && counts -> if (uuid == match.playerA) ratingAAfter else ratingBAfter
                        else -> row.rating
                    }
                    val streak = if (won) row.currentStreak + 1 else 0
                    modeStats.upsert(
                        row.copy(
                            rating = newRating,
                            matchesPlayed = row.matchesPlayed + 1,
                            wins = row.wins + if (won) 1 else 0,
                            losses = row.losses + if (lost) 1 else 0,
                            draws = row.draws + if (winner == null) 1 else 0,
                            playtimeSeconds = row.playtimeSeconds + report.durationSeconds,
                            forfeits = row.forfeits + if (forfeitedBy == uuid) 1 else 0,
                            currentStreak = streak,
                            bestStreak = maxOf(row.bestStreak, streak),
                            peakRating = maxOf(row.peakRating, newRating),
                        )
                    )
                }
            }
        }

        val settled = match.copy(
            status = if (voided) MatchStatus.VOIDED else MatchStatus.COMPLETED,
            outcome = report.outcome,
            ratingAAfter = ratingAAfter,
            ratingBAfter = ratingBAfter,
            durationSeconds = report.durationSeconds,
            teamAScore = report.teamAScore,
            teamBScore = report.teamBScore,
            teamScores = report.teamScores,
            winningTeam = winner.takeIf { rosters.size > 2 },
            forfeitedBy = forfeitedBy,
            completedAt = clock.instant(),
        )
        matches.update(settled)

        // Achievements ride on the ladder: evaluate once the settled match is
        // visible to historyFor (needed for the win-streak milestones). Casual,
        // unrated and voided matches never touch the ladder, so they never award.
        if (counts) match.participants.forEach(::evaluateAchievements)

        return SettleResult.Settled(settled)
    }

    /**
     * Which side won, or null for a draw or a void.
     *
     * [MatchResultReport.winningTeam] is the only thing that can name a winner
     * among three or more sides; two-side matches keep reporting [MatchOutcome]
     * and are translated here, so an old agent's report still settles.
     */
    private fun winningSideOf(report: MatchResultReport): Int? {
        report.winningTeam?.let { return it }
        return when (report.outcome) {
            MatchOutcome.TEAM_A_WIN -> 0
            MatchOutcome.TEAM_B_WIN -> 1
            else -> null
        }
    }

    /**
     * Why this report must not be applied, or null when it is plausible.
     *
     * The per-match token proves the report came from that match's server, not
     * that the server is honest: everything below lands in the match record and
     * [MatchResultReport.durationSeconds] is summed into season playtime, so a
     * compromised or simply buggy agent could otherwise write a negative
     * duration, a score no card can produce, or pin a forfeit on someone who
     * never played the match.
     */
    private fun rejectionReason(match: MatchRecord, report: MatchResultReport): String? {
        if (report.durationSeconds < 0) return "negative duration"
        val maxDuration = match.settings.timeLimitSeconds.coerceAtLeast(0) + MAX_DURATION_OVERRUN_SECONDS
        if (report.durationSeconds > maxDuration) return "duration exceeds ${maxDuration}s"
        for (score in listOf(report.teamAScore, report.teamBScore) + report.teamScores) {
            if (score < 0) return "negative score"
            if (score > MAX_SCORE) return "score exceeds $MAX_SCORE"
        }

        val sides = match.rosters.size
        if (report.teamScores.isNotEmpty() && report.teamScores.size != sides) {
            return "expected $sides team scores, got ${report.teamScores.size}"
        }
        report.winningTeam?.let { winner ->
            if (winner !in 0 until sides) return "winningTeam $winner is not a side of this match"
            if (report.outcome == MatchOutcome.DRAW || report.outcome == MatchOutcome.VOID) {
                return "a ${report.outcome} has no winning team"
            }
            // On a two-side match both fields describe the same fact and must
            // agree, or the record would say one thing and the ladder do
            // another. Above two sides the enum cannot name the winner at all,
            // so winningTeam is simply the authority and the enum only
            // distinguishes decided from drawn or void.
            if (sides == 2) {
                val implied = when (report.outcome) {
                    MatchOutcome.TEAM_A_WIN -> 0
                    MatchOutcome.TEAM_B_WIN -> 1
                    else -> null
                }
                if (implied != null && implied != winner) return "winningTeam contradicts the outcome"
            }
        }
        // Above two sides the outcome enum cannot name a winner, so a decided
        // result must carry winningTeam or it is unattributable.
        if (sides > 2 && report.winningTeam == null &&
            report.outcome != MatchOutcome.DRAW && report.outcome != MatchOutcome.VOID
        ) return "a match with $sides sides must report winningTeam"

        val forfeitedBy = report.forfeitedBy ?: return null
        val uuid = runCatching { UUID.fromString(forfeitedBy) }.getOrNull()
            ?: return "forfeitedBy is not a uuid"
        if (uuid !in match.participants) return "forfeitedBy is not in this match"
        return null
    }

    /** Stats for a player who has no row for that season yet. */
    private fun freshStats(uuid: UUID, season: Int) = SeasonStats(
        uuid = uuid,
        season = season,
        rating = rating.initialRating,
        matchesPlayed = 0,
        wins = 0,
        losses = 0,
        draws = 0,
    )

    /**
     * A mode row for a mode never played. Seeded from whichever rating system
     * governs it, so a first 3v3 starts at the team ladder's initial rating
     * rather than wherever the 1v1 ladder happens to start.
     */
    private fun freshModeStats(uuid: UUID, season: Int, format: MatchFormat) = ModeStatsRecord(
        uuid = uuid,
        season = season,
        format = format,
        rating = if (format.teamSize > 1) teamRating.initialRating else rating.initialRating,
    )

    /**
     * Consecutive wins from the front of the player's newest-first history.
     * Runs on every settle, so it asks only for decided matches and only for
     * [WIN_STREAK_WINDOW] of them — a streak longer than the largest milestone
     * is indistinguishable from one exactly that long.
     */
    fun winStreakOf(uuid: UUID): Int {
        var streak = 0
        for (match in matches.recentDecided(uuid, seasons.currentSeason, limit = WIN_STREAK_WINDOW)) {
            if (match.didWin(uuid)) streak++ else break
        }
        return streak
    }

    /** Longest win streak this player has held this season, across all modes. */
    fun bestStreakOf(uuid: UUID): Int =
        maxOf(winStreakOf(uuid), modesFor(uuid).maxOfOrNull { it.bestStreak } ?: 0)

    /** Award every catalog milestone the player now qualifies for (idempotent). */
    private fun evaluateAchievements(uuid: UUID) {
        val seasonStats = statsFor(uuid)
        val context = AchievementContext(
            stats = players.lifetimeStats(uuid),
            winStreak = winStreakOf(uuid),
            placed = placementMatchesRemaining(seasonStats) <= 0,
        )
        val now = clock.instant()
        for (def in AchievementDef.qualifying(context)) {
            achievements.award(uuid, def.id, now)
        }
    }

    companion object {
        /**
         * Decided matches [winStreakOf] looks back over. One more than the
         * longest streak milestone in the catalog, so the window can always
         * show both the streak and the loss that would have broken it.
         */
        const val WIN_STREAK_WINDOW = 11

        /** 90 minutes — generous cap for lockout 1v1; tune with real data. */
        const val DEFAULT_TIME_LIMIT_SECONDS = 90L * 60L

        /**
         * Slack a reported duration may add on top of the match's own time
         * limit: lobby time, the postgame linger and clock skew all land in it.
         */
        const val MAX_DURATION_OVERRUN_SECONDS = 60L * 60L

        /** A bingo card is 5x5, so neither side can claim more than 25 objectives. */
        const val MAX_SCORE = 25

        /**
         * How long a match may stay PENDING before the client gives up waiting
         * for its server. The orchestrator's reaper uses this plus a grace
         * period, so the two can never disagree about when a match is dead.
         */
        const val PROVISION_TIMEOUT_SECONDS = 180L

        /** Grace after [PROVISION_TIMEOUT_SECONDS] before the reaper voids. */
        const val PROVISION_REAP_GRACE_SECONDS = 30L
    }
}
