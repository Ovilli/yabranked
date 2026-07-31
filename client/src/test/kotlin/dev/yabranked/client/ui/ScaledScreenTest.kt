package dev.yabranked.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MC-free half of [ScaledScreen]: which whole GUI scale a window can afford.
 * Nothing here constructs a screen, so it runs without a game.
 */
class ScaledScreenTest {

    private fun fits(window: Pair<Int, Int>, step: Int): Boolean =
        window.first / step >= ScaledScreen.DESIGN_WIDTH && window.second / step >= ScaledScreen.DESIGN_HEIGHT

    @Test
    fun `a scale that already fits is left alone`() {
        // 1080p at GUI scale 2 is 960x540 — more than the design needs.
        assertEquals(2, ScaledScreen.fittingScale(1920, 1080, 2))
    }

    @Test
    fun `a scale too big for the design steps down until it fits`() {
        // 1080p at 4 is 480x270: the ranked menu ran off the bottom of it.
        val step = ScaledScreen.fittingScale(1920, 1080, 4)
        assertEquals(3, step)
        assertTrue(fits(1920 to 1080, step))
    }

    @Test
    fun `it never steps past the largest scale that fits`() {
        for (guiScale in 1..9) {
            val step = ScaledScreen.fittingScale(2560, 1440, guiScale)
            assertTrue(step <= guiScale, "never enlarges past the player's own choice")
            if (step < guiScale) {
                assertTrue(fits(2560 to 1440, step), "the scale it settled on has to fit")
                assertTrue(!fits(2560 to 1440, step + 1), "and it must be the largest such scale")
            }
        }
    }

    @Test
    fun `a window too small for the design at any scale still lands on 1`() {
        // Complete but small beats laid out past the edge of the window.
        assertEquals(1, ScaledScreen.fittingScale(400, 300, 3))
        assertEquals(1, ScaledScreen.fittingScale(400, 300, 1))
    }
}
