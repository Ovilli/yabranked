package dev.yabranked.client

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What happens to a session the backend has stopped accepting.
 *
 * The client used to keep it. `RankedState.isAuthenticated` reads
 * `backend.session != null`, so a dead token still counted as being signed in:
 * the ranked menu hid its Log in button, every screen said "Session expired —
 * sign in again", and there was nothing on screen that could.
 */
class SessionExpiryTest {

    private lateinit var server: HttpServer
    private val unauthorizedCalls = AtomicInteger()

    /** 401 for everything except the sign-in itself. */
    private fun start(authedStatus: Int) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/auth/session") { exchange ->
            val body = """
                {"token":"tok","profile":{"uuid":"a","name":"Player","rating":1000,
                "placementMatchesRemaining":0,"wins":0,"losses":0,"draws":0}}
            """.trimIndent().replace("\n", "")
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.createContext("/v1/friends") { exchange ->
            val body = if (authedStatus == 200) "{}" else """{"error":"nope"}"""
            exchange.sendResponseHeaders(authedStatus, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
    }

    private fun client(): BackendClient {
        val backend = BackendClient("http://127.0.0.1:${server.address.port}", "0.1.0")
        backend.onUnauthorized = { unauthorizedCalls.incrementAndGet() }
        val result = backend.authenticate("Player") { }
        assertTrue(result is BackendClient.AuthResult.Ok, "the fake sign-in should succeed, got $result")
        return backend
    }

    @BeforeTest
    fun reset() {
        unauthorizedCalls.set(0)
    }

    @AfterTest
    fun stop() {
        if (::server.isInitialized) server.stop(0)
    }

    @Test
    fun `a refused request drops the session so the login button comes back`() {
        start(authedStatus = 401)
        val backend = client()
        assertTrue(backend.session != null, "signing in should have produced a session")

        val fetched = backend.fetchFriends()

        assertEquals(BackendClient.Fetch.Unauthorized, fetched)
        assertNull(backend.session, "a 401 must not leave a dead session counting as one")
        assertEquals(1, unauthorizedCalls.get(), "the client was not told its session had gone")
    }

    @Test
    fun `a burst of refusals is one piece of news`() {
        start(authedStatus = 403)
        val backend = client()

        repeat(4) { backend.fetchFriends() }

        // Several screens poll at once, so one expiry produces a burst of
        // refusals — re-authenticating once per screen is not the intent.
        assertEquals(1, unauthorizedCalls.get(), "every refusal raised its own alarm")
    }

    @Test
    fun `a working session is left alone`() {
        start(authedStatus = 200)
        val backend = client()

        backend.fetchFriends()

        assertTrue(backend.session != null, "a successful request dropped the session")
        assertEquals(0, unauthorizedCalls.get())
    }
}
