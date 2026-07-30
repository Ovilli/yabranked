package dev.yabranked.backend.queue

import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import dev.yabranked.backend.store.MatchRecord
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.proto.MatchFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Match store that fails the first [failures] inserts, then behaves normally. */
private class FlakyMatchStore(private var failures: Int) : MatchStore {
    private val delegate = InMemoryMatchStore()
    var inserts = 0
        private set

    override fun get(id: UUID): MatchRecord? = delegate.get(id)

    override fun insert(record: MatchRecord) {
        inserts++
        if (failures > 0) {
            failures--
            error("simulated store outage")
        }
        delegate.insert(record)
    }

    override fun update(record: MatchRecord) = delegate.update(record)

    override fun historyFor(player: UUID, season: Int, limit: Int) =
        delegate.historyFor(player, season, limit)

    override fun recentDecided(player: UUID, season: Int, limit: Int) =
        delegate.recentDecided(player, season, limit)

    override fun between(a: UUID, b: UUID, season: Int, limit: Int) =
        delegate.between(a, b, season, limit)

    override fun unsettled(): List<MatchRecord> = delegate.unsettled()

    override fun liveFor(player: UUID): MatchRecord? = delegate.liveFor(player)
}

class QueueServiceTest {

    private val players = InMemoryPlayerStore()

    private fun serviceWith(matches: MatchStore): QueueService {
        val matchService = MatchService(players, matches, EloRatingSystem(), SeasonService())
        return QueueService(MatchmakingQueue(), matchService)
    }

    private suspend fun queueTwo(service: QueueService): Pair<UUID, UUID> {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        service.join(a, 1000, MatchFormat.LOCKOUT_1V1)
        service.join(b, 1000, MatchFormat.LOCKOUT_1V1)
        return a to b
    }

    @Test
    fun `join reports false for a player who is already queued`() = runTest {
        val service = serviceWith(InMemoryMatchStore())
        val player = UUID.randomUUID()

        assertTrue(service.join(player, 1000, MatchFormat.LOCKOUT_1V1))
        assertFalse(
            service.join(player, 1000, MatchFormat.LOCKOUT_1V1),
            "a second session must not be told it owns the queue entry",
        )
    }

    @Test
    fun `a failed match creation puts both players back in the queue`() = runTest {
        val store = FlakyMatchStore(failures = 1)
        val service = serviceWith(store)
        val (a, b) = queueTwo(service)

        service.tickOnce() // insert throws, players must not be dropped

        assertEquals(1, store.inserts)
        assertNotNull(service.snapshot(a), "player A was dropped from the queue")
        assertNotNull(service.snapshot(b), "player B was dropped from the queue")

        service.tickOnce() // store recovered: they match this time
        assertEquals(2, store.inserts)
        assertEquals(null, service.snapshot(a))
    }

    @Test
    fun `the tick loop survives a failing tick`() {
        val store = FlakyMatchStore(failures = 1)
        val service = serviceWith(store)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            service.start(scope)
            kotlinx.coroutines.runBlocking { queueTwo(service) }

            // first tick throws inside createMatch, later ticks must still run
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                if (store.inserts >= 2) break
                Thread.sleep(50)
            }
            if (store.inserts < 2) fail("matchmaking stopped after a failing tick")
            assertTrue(service.isRunning, "tick job died")
        } finally {
            scope.cancel()
        }
    }
}
