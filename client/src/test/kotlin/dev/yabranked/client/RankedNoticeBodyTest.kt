package dev.yabranked.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How much of a notice's body survives.
 *
 * Half of what appears in a notice is a refusal written by the backend, and a
 * one-line body cut those off with an ellipsis — reliably removing the half
 * that said why.
 */
class RankedNoticeBodyTest {

    /** Stand-in for a font: 6px a character, formatting codes free. */
    private val measure: (String) -> Int = { it.replace(Regex("§."), "").length * 6 }

    private val ellipsise: (String, Int) -> String = { s, w ->
        var t = s
        while (t.isNotEmpty() && measure("$t…") > w) t = t.dropLast(1)
        "$t…"
    }

    private fun lines(body: String, width: Int) =
        RankedNotice.bodyLines(body, width, measure, ellipsise)

    @Test
    fun `a short body stays one line`() {
        assertEquals(listOf("Request sent"), lines("Request sent", 180))
    }

    @Test
    fun `a body that needs two lines gets two`() {
        val body = "You already have a pending request to that player"
        val out = lines(body, 180)

        assertEquals(2, out.size, "expected two lines, got $out")
        assertTrue(out.none { it.endsWith("…") }, "nothing should have been cut: $out")
        assertEquals(body.split(' '), out.joinToString(" ").split(' '), "words were lost")
    }

    @Test
    fun `never more than two lines, and the second says it was cut`() {
        val body = "The backend refused this because the season has already rolled over " +
            "and the match you are naming belongs to the previous one"
        val out = lines(body, 120)

        assertEquals(2, out.size, "the stack must not grow past two lines: $out")
        assertTrue(out.last().endsWith("…"), "a truncated body must say so: ${out.last()}")
        for (line in out) {
            assertTrue(measure(line) <= 120, "line wider than the notice: \"$line\"")
        }
    }
}
