package dev.yabranked.backend.ops

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.sql.DataSource

/**
 * Readiness inputs, all lambdas so the probes can be exercised without a
 * database or a live matchmaking loop.
 *
 * The distinction that matters: liveness is "the process exists", readiness is
 * "this process can actually serve a match". A backend whose matchmaking tick
 * job died answers every endpoint with 200 while quietly matching nobody —
 * that instance must fail readiness so an orchestrator replaces it.
 */
class ReadinessChecks(
    private val matchmakingRunning: () -> Boolean,
    /** True from the start of a graceful shutdown; drains traffic away from us. */
    private val draining: () -> Boolean = { false },
    /** null when the backend runs on in-memory stores — there is nothing to reach. */
    private val databaseReachable: (suspend () -> Boolean)? = null,
) {
    /** Failed check names, empty when ready. */
    suspend fun failures(): List<String> = buildList {
        if (draining()) add("draining")
        if (!matchmakingRunning()) add("matchmaking")
        if (databaseReachable?.invoke() == false) add("database")
    }
}

/**
 * Blocking connection probe for [ReadinessChecks]; hops off the event loop
 * because it borrows a pooled connection and may wait for one.
 */
fun databaseProbe(dataSource: DataSource): suspend () -> Boolean = {
    withContext(Dispatchers.IO) {
        runCatching {
            dataSource.connection.use { it.isValid(PROBE_TIMEOUT_SECONDS) }
        }.getOrDefault(false)
    }
}

private const val PROBE_TIMEOUT_SECONDS = 2

@Serializable
private data class HealthResponse(val status: String, val failing: List<String> = emptyList())

/** Deliberately independent of ContentNegotiation: the probes must work bare. */
private val healthJson = Json

/**
 * `/health` answers as long as the process is alive; `/ready` answers 503
 * unless the instance can actually take work.
 */
fun Application.healthRoutes(checks: ReadinessChecks) {
    routing {
        get("/health") {
            call.respondText(
                healthJson.encodeToString(HealthResponse("up")),
                ContentType.Application.Json,
            )
        }
        get("/ready") {
            val failing = checks.failures()
            call.respondText(
                healthJson.encodeToString(
                    HealthResponse(if (failing.isEmpty()) "ready" else "not_ready", failing)
                ),
                ContentType.Application.Json,
                if (failing.isEmpty()) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable,
            )
        }
    }
}
