package dev.yabranked.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read-outcome taxonomy the screens branch on. It exists because collapsing
 * every failure into null left every screen able to say nothing better than
 * "could not load", so the thing worth pinning down is that the cases stay
 * distinguishable and each carries a line a player can act on.
 */
class BackendFetchTest {

    private val failures = listOf(
        BackendClient.Fetch.Offline,
        BackendClient.Fetch.Unauthorized,
        BackendClient.Fetch.Outdated,
        BackendClient.Fetch.Failed(500),
    )

    @Test
    fun `orElse unwraps a success`() {
        assertEquals("value", BackendClient.Fetch.Ok("value").orElse("fallback"))
    }

    @Test
    fun `orElse yields the fallback for every failure`() {
        // the achievement strip and versus record use this: no error card, just
        // an empty embellishment
        for (failure in failures) {
            assertEquals("fallback", failure.orElse("fallback"), "$failure leaked through orElse")
        }
        assertEquals(emptyList(), BackendClient.Fetch.Offline.orElse(emptyList<String>()))
    }

    @Test
    fun `each failure says something different and actionable`() {
        val messages = failures.map { it.message }

        assertTrue(messages.all { it.isNotBlank() }, "a blank message renders as an empty error card")
        assertEquals(messages.size, messages.toSet().size, "two failures read identically: $messages")
    }

    @Test
    fun `an unmapped status is reported with its code`() {
        // 500 vs 404 is the only clue a player can pass on to a bug report
        assertTrue("500" in BackendClient.Fetch.Failed(500).message)
        assertTrue("404" in BackendClient.Fetch.Failed(404).message)
    }

    @Test
    fun `a screen can tell success from failure on the type alone`() {
        val outcomes: List<BackendClient.Fetch<String>> = failures + BackendClient.Fetch.Ok("value")

        // exhaustive over the sealed hierarchy — a new failure case added to
        // Fetch stops compiling here until it is given a rendering
        val rendered = outcomes.map {
            when (it) {
                is BackendClient.Fetch.Ok -> "ok:${it.value}"
                is BackendClient.Fetch.Error -> "error:${it.message}"
            }
        }

        assertEquals(1, rendered.count { it.startsWith("ok:") })
        assertEquals(failures.size, rendered.count { it.startsWith("error:") })
        assertEquals("ok:value", rendered.last())
    }
}
