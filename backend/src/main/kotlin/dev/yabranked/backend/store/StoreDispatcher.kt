package dev.yabranked.backend.store

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Threads for blocking store work.
 *
 * Every store call is synchronous JDBC and Ktor runs handlers on the event
 * loop, so a slow query there stalls every other connection that loop is
 * serving — not just the caller's. All of it hops here first.
 *
 * A whole [TransactionRunner.transaction] must fit inside a single hop: the
 * connection is bound to the thread that opened it (see `Database`), so
 * switching dispatchers mid-transaction silently splits it in two.
 */
object StoreDispatchers {

    /** Matches [Database.DEFAULT_POOL_SIZE]; used when there is no pool at all. */
    const val DEFAULT_PARALLELISM = Database.DEFAULT_POOL_SIZE

    /**
     * Bounded view of [Dispatchers.IO]. Sized to the connection pool: more
     * threads than connections only ever queue inside Hikari, where the wait
     * is invisible to metrics.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun bounded(parallelism: Int): CoroutineDispatcher =
        Dispatchers.IO.limitedParallelism(parallelism, "yabranked-store")

    /** Shared default for callers that were never handed a configured one. */
    val default: CoroutineDispatcher by lazy { bounded(DEFAULT_PARALLELISM) }
}
