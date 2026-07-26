package dev.yabranked.backend.ops

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.callid.generate
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

/** Probe and scrape traffic, one line a second forever; never worth logging. */
private val QUIET_PATHS = setOf("/health", "/ready", "/metrics")

private const val CALL_ID_DICTIONARY = "abcdefghijklmnopqrstuvwxyz0123456789"
private const val CALL_ID_LENGTH = 12

/**
 * Request logging with a call id.
 *
 * Every log line a request produces carries the same `callId` through the MDC
 * (see logback.xml), so one player's failed match can be pulled out of a
 * process serving a hundred others. The id comes from the caller's
 * X-Request-Id when it sends one — that keeps the trace joined up across a
 * proxy — and is echoed back on the response either way.
 */
fun Application.requestLogging() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate(length = CALL_ID_LENGTH, dictionary = CALL_ID_DICTIONARY)
        // The retrieved id is caller-controlled and ends up in every log line
        // and back on the response, so hold it to the generator's own alphabet
        // — an id carrying newlines would forge log entries.
        verify { id -> id.length in 1..64 && id.all { it in CALL_ID_DICTIONARY } }
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("callId")
        filter { call -> call.request.path() !in QUIET_PATHS }
        format { call ->
            val status = call.response.status()?.value?.toString() ?: "unhandled"
            "${call.request.httpMethod.value} ${call.request.path()} -> $status"
        }
    }
}
