package dev.yabranked.backend.ops

import dev.yabranked.proto.MatchFormat
import dev.yabranked.proto.MatchOutcome
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the matchmaking loop reports. An interface rather than a registry so
 * `QueueService` stays testable without a metrics backend.
 */
interface MatchmakingMetrics {
    fun tickCompleted(nanos: Long)
    fun tickFailed()
    fun queueDepth(format: MatchFormat, depth: Int)

    companion object {
        /** Default everywhere except `main`. */
        val NONE: MatchmakingMetrics = object : MatchmakingMetrics {
            override fun tickCompleted(nanos: Long) {}
            override fun tickFailed() {}
            override fun queueDepth(format: MatchFormat, depth: Int) {}
        }
    }
}

/** What the orchestrator reports about provisioning match containers. */
interface OrchestratorMetrics {
    fun provisioned()
    fun provisionFailed(reason: String)
    fun provisionTimedOut()

    /** A container that had already gone live was found dead. Alert on any rate
     *  at all: it means matches are being voided by infrastructure, not played. */
    fun serverDied()

    companion object {
        val NONE: OrchestratorMetrics = object : OrchestratorMetrics {
            override fun provisioned() {}
            override fun provisionFailed(reason: String) {}
            override fun provisionTimedOut() {}
            override fun serverDied() {}
        }
    }
}

/**
 * Every number worth having at 3am, in one Prometheus registry: how deep the
 * queue is per format, whether matchmaking is still ticking and how long a
 * tick takes, how many matches were created / settled / voided, and how
 * container provisioning is going. HTTP counts and latencies come from Ktor's
 * own Micrometer plugin (see [metricsRoutes]).
 */
class Metrics(
    val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
) : MatchmakingMetrics, OrchestratorMetrics {

    init {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
    }

    /**
     * Gauges cannot be polled from a coroutine holding the queue mutex, so the
     * tick pushes each format's depth into the value this gauge reads.
     */
    private val queueDepths = ConcurrentHashMap<MatchFormat, AtomicInteger>()

    private val tickTimer = Timer.builder("yabranked.matchmaking.tick")
        .description("time spent in one matchmaking tick")
        .publishPercentileHistogram()
        .register(registry)

    private val tickFailures = Counter.builder("yabranked.matchmaking.tick.failures")
        .description("matchmaking ticks that threw; the loop survives them, alert on the rate")
        .register(registry)

    private val matchesCreated = Counter.builder("yabranked.matches.created")
        .register(registry)

    private val matchesSettled = ConcurrentHashMap<String, Counter>()

    private val provisions = ConcurrentHashMap<String, Counter>()

    override fun tickCompleted(nanos: Long) = tickTimer.record(nanos, TimeUnit.NANOSECONDS)

    override fun tickFailed() = tickFailures.increment()

    override fun queueDepth(format: MatchFormat, depth: Int) {
        queueDepths.computeIfAbsent(format) { key ->
            AtomicInteger().also {
                Gauge.builder("yabranked.queue.depth", it, AtomicInteger::toDouble)
                    .description("players waiting for this format")
                    .tag("format", key.name)
                    .register(registry)
            }
        }.set(depth)
    }

    fun matchCreated() = matchesCreated.increment()

    /** A settled match, tagged by outcome; a null outcome is a void. */
    fun matchSettled(outcome: MatchOutcome?) {
        val name = if (outcome == null || outcome == MatchOutcome.VOID) {
            "yabranked.matches.voided"
        } else {
            "yabranked.matches.settled"
        }
        val tag = outcome?.name ?: MatchOutcome.VOID.name
        matchesSettled.computeIfAbsent("$name/$tag") {
            Counter.builder(name).tag("outcome", tag).register(registry)
        }.increment()
    }

    override fun provisioned() = provision("ok")

    override fun provisionFailed(reason: String) = provision(reason)

    /** The container came up but the agent never reported ready in time. */
    override fun provisionTimedOut() = provision("timeout")

    override fun serverDied() = provision("died")

    private fun provision(result: String) {
        provisions.computeIfAbsent(result) {
            Counter.builder("yabranked.match.provisions")
                .description("match container provisioning attempts by result")
                .tag("result", it)
                .register(registry)
        }.increment()
    }

    fun close() = registry.close()
}

/** Ktor's per-request timer plus the scrape endpoint Prometheus reads. */
fun Application.metricsRoutes(metrics: Metrics, path: String = "/metrics") {
    install(MicrometerMetrics) {
        registry = metrics.registry
    }
    routing {
        get(path) {
            call.respondText(metrics.registry.scrape(), ContentType.Text.Plain)
        }
    }
}
