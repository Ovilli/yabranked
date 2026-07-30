package dev.yabranked.backend.rating

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.LockingTransactionRunner
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.TransactionRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.hours

/**
 * Inactivity decay. A rating is a claim about how you play *now*; without decay
 * a top-ten player holds their rank forever by never queueing again.
 *
 * Deliberately narrow: only ratings above [protectedRating] decay, and never
 * below it. Decay is there to keep the visible top of the ladder honest, not to
 * punish the casual middle for taking a month off, and a player who comes back
 * still lands in the tier they earned.
 */
data class DecayPolicy(
    /** Days of inactivity that cost nothing. */
    val graceDays: Long = 14,
    /** Rating lost per idle day once the grace period is over. */
    val pointsPerDay: Int = 10,
    /** Nothing at or below this decays, and decay never pushes below it. */
    val protectedRating: Int = Tier.EMERALD.floor,
)

/** What one decay charge does to a row. */
data class DecayCharge(
    val rating: Int,
    /**
     * New watermark: idle time up to here has been paid for. Charging whole
     * days only and remembering where we stopped is what keeps repeated sweeps
     * from billing the same idle week twice.
     */
    val chargedThrough: Instant,
)

/** Pure decay arithmetic; persisting the result is [DecaySweep]'s problem. */
class RatingDecay(val policy: DecayPolicy = DecayPolicy()) {

    /**
     * What [rating] owes at [now], or null when nothing is due. A null
     * [lastPlayedAt] is not evidence of inactivity — rows written before the
     * column existed and rows a season rollover seeded both look like that — so
     * they are left alone until their owner plays a match.
     */
    fun charge(
        rating: Int,
        lastPlayedAt: Instant?,
        decayedThrough: Instant?,
        now: Instant,
    ): DecayCharge? {
        if (rating <= policy.protectedRating || lastPlayedAt == null) return null
        val graceEnds = lastPlayedAt.plus(Duration.ofDays(policy.graceDays))
        val from = maxOf(graceEnds, decayedThrough ?: graceEnds)
        val days = Duration.between(from, now).toDays()
        if (days <= 0) return null
        val decayed = (rating - days * policy.pointsPerDay)
            .coerceAtLeast(policy.protectedRating.toLong())
            .toInt()
        return DecayCharge(decayed, from.plus(Duration.ofDays(days)))
    }
}

/**
 * Applies [RatingDecay] to the live ladder. Decay is persisted rather than
 * applied on read: the leaderboard is ordered by the stored rating, so a
 * decayed player has to actually move down the column to move down the ladder.
 *
 * Only the top of the ladder is scanned — by construction nobody at or below
 * [DecayPolicy.protectedRating] can decay, and the rows come back rating-sorted.
 */
class DecaySweep(
    private val players: PlayerStore,
    private val seasons: SeasonService,
    private val rating: RatingSystem,
    private val decay: RatingDecay = RatingDecay(),
    private val clock: Clock = Clock.systemUTC(),
    /** Ceiling on rows examined per sweep; decay never reaches further down. */
    private val scanLimit: Int = DEFAULT_SCAN_LIMIT,
    private val interval: kotlin.time.Duration = 6.hours,
    /**
     * The store's transaction boundary — the same one the settle path uses, so
     * a charge and a settle for the same row serialize instead of racing. Pass
     * [MatchService.transactionRunner]; the default is only useful in tests,
     * where nothing else is writing.
     */
    private val transactions: TransactionRunner = LockingTransactionRunner(),
) {
    private val log = LoggerFactory.getLogger("decay")
    private var job: Job? = null

    /** Charges every stale row in the current season; returns how many moved. */
    fun sweep(): Int {
        val now = clock.instant()
        val season = seasons.currentSeason
        // Players still in placements are not on the ladder and hold no rank to
        // protect, so they are not worth decaying.
        val ladder = players.topByRating(season, limit = scanLimit, minMatches = rating.placementMatches)
        var changed = 0
        for (scanned in ladder) {
            // sorted by rating: once we drop to the protected band, so has the rest
            if (scanned.rating <= decay.policy.protectedRating) break
            // The scan is a plain, unlocked read of the whole ladder, and
            // upsertStats replaces every column. Charging the copy it handed
            // back wrote the row as it was *before* any match that settled in
            // between — the rating change, the win and the playtime all
            // silently reverted. Re-read the row under its lock and charge that.
            val moved = transactions.transaction {
                val stats = players.getStatsForUpdate(scanned.uuid, season) ?: return@transaction false
                val charge = decay.charge(stats.rating, stats.lastPlayedAt, stats.decayedThrough, now)
                    ?: return@transaction false
                players.upsertStats(stats.copy(rating = charge.rating, decayedThrough = charge.chargedThrough))
                true
            }
            if (moved) changed++
        }
        if (changed > 0) log.info("decayed {} inactive rating(s) in season {}", changed, season)
        return changed
    }

    fun start(scope: CoroutineScope) {
        check(job == null) { "already started" }
        job = scope.launch {
            while (true) {
                // A failed sweep must not end the loop — the ladder would then
                // silently stop decaying for the life of the process.
                try {
                    sweep()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log.error("decay sweep failed; continuing", e)
                }
                delay(interval)
            }
        }
    }

    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
    }

    companion object {
        /**
         * Deep enough to cover everyone who could plausibly sit above the
         * protected rating; the sweep stops early anyway once the sorted rows
         * drop into it.
         */
        const val DEFAULT_SCAN_LIMIT = 1000
    }
}
