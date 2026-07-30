package dev.yabranked.backend.social

import dev.yabranked.proto.PresenceState
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is online and what they are doing, for the friends list.
 *
 * Deliberately in-memory and process-local: presence is only true while a
 * socket is open, so persisting it would mostly persist lies — a backend that
 * restarts would come back claiming a hundred players are still in a menu.
 *
 * Entries expire on their own ([ttl]) so a socket that dies without unwinding
 * its `finally` cannot leave a player online forever.
 */
class Presence(
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofSeconds(90),
) {
    private class Entry(val state: PresenceState, val touchedAt: Instant)

    private val entries = ConcurrentHashMap<UUID, Entry>()

    /** When [sweepIfDue] last ran; null until the first write. */
    private val lastSweep = java.util.concurrent.atomic.AtomicReference<Instant?>(null)

    /** Record (or refresh) what [player] is doing. */
    fun set(player: UUID, state: PresenceState) {
        if (state == PresenceState.OFFLINE) {
            entries.remove(player)
            return
        }
        entries[player] = Entry(state, clock.instant())
        sweepIfDue()
    }

    /** Extend the current state's lifetime without changing it. */
    fun touch(player: UUID) {
        val current = entries[player] ?: return
        entries[player] = Entry(current.state, clock.instant())
    }

    fun clear(player: UUID) {
        entries.remove(player)
    }

    fun stateOf(player: UUID): PresenceState {
        val entry = entries[player] ?: return PresenceState.OFFLINE
        if (Duration.between(entry.touchedAt, clock.instant()) > ttl) {
            entries.remove(player, entry)
            return PresenceState.OFFLINE
        }
        return entry.state
    }

    fun isOnline(player: UUID): Boolean = stateOf(player) != PresenceState.OFFLINE

    /** States for many players at once; absent players are simply offline. */
    fun statesOf(players: Collection<UUID>): Map<UUID, PresenceState> =
        players.associateWith(::stateOf).filterValues { it != PresenceState.OFFLINE }

    /**
     * Entries currently tracked; exposed for a metrics gauge and so tests can
     * assert the sweep runs. At most one [ttl] behind the truth, since that is
     * how often [sweepIfDue] fires.
     */
    val onlineCount: Int get() = entries.size

    /**
     * Drop expired entries, at most once per [ttl].
     *
     * The sweep used to hang off [onlineCount], which nothing in the running
     * server ever reads — so in practice it never ran. [stateOf] expires an
     * entry it happens to be asked about, but a player who queued once and then
     * never appeared in anyone's friends list or party is never asked about
     * again, and their entry stayed for the life of the process. Writing
     * presence is the one path that always runs, so the sweep hangs off that.
     */
    private fun sweepIfDue() {
        val now = clock.instant()
        val previous = lastSweep.get()
        if (previous != null && Duration.between(previous, now) < ttl) return
        if (!lastSweep.compareAndSet(previous, now)) return
        val cutoff = now.minus(ttl)
        entries.entries.removeIf { it.value.touchedAt.isBefore(cutoff) }
    }
}
