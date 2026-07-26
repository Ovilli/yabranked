package dev.yabranked.backend.ops

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Ordered drain for SIGTERM.
 *
 * The process used to die where it stood: queued clients had their sockets cut
 * with no explanation, the matchmaking tick vanished mid-pairing and the
 * connection pool was never closed. Steps run in registration order — stop
 * taking new work first, tell whoever is waiting, only then close what holds
 * resources — and a step that throws never skips the ones after it.
 */
class GracefulShutdown {
    private val log = LoggerFactory.getLogger("shutdown")
    private val steps = mutableListOf<Pair<String, suspend () -> Unit>>()
    private val started = AtomicBoolean(false)

    /** True from the moment the drain begins; readiness reports not-ready on it. */
    val isDraining: Boolean get() = started.get()

    fun step(name: String, action: suspend () -> Unit) {
        synchronized(steps) { steps += name to action }
    }

    /** Idempotent: the JVM only runs the hook once, but tests call it too. */
    fun drain() {
        if (!started.compareAndSet(false, true)) return
        val ordered = synchronized(steps) { steps.toList() }
        runBlocking {
            for ((name, action) in ordered) {
                try {
                    action()
                } catch (e: Throwable) {
                    log.error("shutdown step '$name' failed; continuing", e)
                }
            }
        }
        log.info("shutdown complete ({} steps)", ordered.size)
    }

    /** Installs the JVM hook that runs [drain] on SIGTERM / Ctrl-C. */
    fun installHook() {
        Runtime.getRuntime().addShutdownHook(Thread(::drain, "yabranked-shutdown"))
    }
}
