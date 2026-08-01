package dev.yabranked.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Line breaking for [Ui.messageCard]. The draw call needs a game, the arithmetic
 * does not — and the arithmetic is where the bug was: a message wider than its
 * card was drawn as one line straight through both borders.
 */
class UiWrapTest {

    /** Stand-in for a font: 6px a character, close enough to MC's default. */
    private val measure: (String) -> Int = { it.replace(Regex("§."), "").length * 6 }

    private fun wrap(text: String, maxWidth: Int) = Ui.wrap(text, maxWidth, measure)

    @Test
    fun `text that already fits is one line`() {
        assertEquals(listOf("short"), wrap("short", 200))
    }

    @Test
    fun `every produced line fits the width`() {
        // The replay library's empty state, the longest message in the client.
        val text = "§7Play a match, then open it from History to download its replay."
        val lines = wrap(text, 284)
        assertTrue(lines.size > 1, "expected a break, got ${lines.size} line(s)")
        for (line in lines) {
            assertTrue(measure(line) <= 284, "line over the card: \"$line\" (${measure(line)}px)")
        }
    }

    @Test
    fun `wrapping loses no words`() {
        val text = "the match server could not be started, so nobody was sent anywhere"
        val words = wrap(text, 120).joinToString(" ").split(' ').filter { it.isNotEmpty() }
        assertEquals(text.split(' '), words)
    }

    @Test
    fun `a formatting code carries onto the lines it opened`() {
        val lines = wrap("§cthe backend refused this replay for a reason of some length", 90)
        assertTrue(lines.size > 1)
        for (line in lines) assertTrue(line.startsWith("§c"), "line lost its colour: \"$line\"")
    }

    @Test
    fun `a word too long to fit is ellipsised rather than dropped`() {
        val long = "a".repeat(60)
        val lines = Ui.wrap("$long tail", 60, measure, ellipsise = { s, _ -> s.take(9) + "…" })
        assertTrue(lines.first().endsWith("…"), "expected an ellipsis, got \"${lines.first()}\"")
        assertTrue(lines.any { it.contains("tail") }, "the rest of the message was dropped")
    }
}
