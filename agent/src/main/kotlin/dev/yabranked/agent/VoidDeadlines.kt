package dev.yabranked.agent

/**
 * What the periodic pre-game check decided to do about a match that has not
 * started yet.
 */
sealed interface VoidDecision {
    /** Nothing is wrong yet. */
    data object KeepWaiting : VoidDecision

    /** Somebody never connected. [missing] is who, for the log and the message. */
    data class NoShow(val missing: List<AgentConfig.ExpectedPlayer>) : VoidDecision

    /** Everyone who was coming arrived, and the game still never got going. */
    data object NeverStarted : VoidDecision
}

/**
 * The two deadlines a match has before it starts, and which one has expired.
 *
 * Pulled out of the scheduled lambda it used to live in so it can be asserted
 * rather than reproduced. Reproducing it costs a container, a match and two
 * clients, and the bug it hides is not a crash — it is a live match being
 * told its players never turned up.
 *
 * They are two deadlines because there are two failures and only one of them is
 * anybody's fault:
 *
 *  - **Somebody never turned up.** Decided by who has *connected*. This used to
 *    be inferred from the game phase instead, which only leaves
 *    `WAITING_FOR_PLAYERS` when YAB reaches `PLAYING` — and on a CPU-limited
 *    container those are minutes apart, because YAB's spawn search runs to a
 *    60s timeout of its own and the chunk preload after it takes tens of
 *    seconds. An observed match with both players on their teams for 80 seconds
 *    was voided six seconds before its countdown ended, telling both of them
 *    their opponent had never connected.
 *  - **Everyone arrived and the game never started.** That is a broken match
 *    server, it needs its own and much longer deadline, and it still has to end
 *    — the orchestrator's ready-timeout reaper only ever looks at `PENDING`
 *    matches, so a game stuck in `STARTING` would otherwise hold both players
 *    forever.
 */
object VoidDeadlines {

    /**
     * Extra time, on top of the no-show wait, before a match where everybody is
     * present but nothing has started is given up on.
     */
    const val START_GRACE_SECONDS = 180L

    /** The deadline for "present, but no game", derived from the no-show wait. */
    fun startDeadlineSeconds(noShowTimeoutSeconds: Long): Long =
        noShowTimeoutSeconds + START_GRACE_SECONDS

    /**
     * @param waitedSeconds since the agent reported ready — *not* since the
     *   container started, because players cannot connect before that point.
     * @param missing everyone on the roster who has not connected.
     * @param startRequested whether YAB has already been told to start. Once it
     *   has, nobody is a no-show: everyone was here and on their teams, so a
     *   disconnect after that is not "your opponent never connected".
     */
    fun evaluate(
        waitedSeconds: Long,
        missing: List<AgentConfig.ExpectedPlayer>,
        startRequested: Boolean,
        noShowTimeoutSeconds: Long,
        startDeadlineSeconds: Long = startDeadlineSeconds(noShowTimeoutSeconds),
    ): VoidDecision = when {
        missing.isNotEmpty() && !startRequested && waitedSeconds >= noShowTimeoutSeconds ->
            VoidDecision.NoShow(missing)

        // Either everybody is here, or the start was already requested and
        // somebody has since dropped. Both are "this server never got a game
        // going", and both still have to end.
        (missing.isEmpty() || startRequested) && waitedSeconds >= startDeadlineSeconds ->
            VoidDecision.NeverStarted

        else -> VoidDecision.KeepWaiting
    }

    /**
     * When to tell whoever did turn up what they are waiting for.
     *
     * Sitting alone in a bingo lobby with no message is indistinguishable from a
     * broken match server, which is what it kept being reported as. An offset is
     * only used when there is still time left to announce — "voided in 0s" is
     * not a warning, it is a countdown that has already finished.
     */
    fun announcementOffsets(noShowTimeoutSeconds: Long): List<Long> =
        DEFAULT_ANNOUNCEMENTS.filter { noShowTimeoutSeconds > it }

    private val DEFAULT_ANNOUNCEMENTS = listOf(15L, 45L)
}
