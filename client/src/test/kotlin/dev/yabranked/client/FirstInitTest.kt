package dev.yabranked.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FirstInitTest {

    @Test
    fun `the block runs on the first call only`() {
        val gate = FirstInit()
        var runs = 0

        // Screen.init() is re-entered on every window resize; the guard is what
        // stops one open sound and one backend fetch per frame of a drag.
        repeat(10) { gate.once { runs++ } }

        assertEquals(1, runs)
    }

    @Test
    fun `a later call cannot smuggle in different work`() {
        val gate = FirstInit()
        var first = 0
        var second = 0

        gate.once { first++ }
        gate.once { second++ }

        assertEquals(1, first)
        assertEquals(0, second, "the guard is per instance, not per block")
    }

    @Test
    fun `each instance gates independently`() {
        // one guard per screen — arming one screen must not disarm another
        val a = FirstInit()
        val b = FirstInit()
        var runs = 0

        a.once { runs++ }
        b.once { runs++ }

        assertEquals(2, runs)
    }

    @Test
    fun `a throwing block still counts as done`() {
        val gate = FirstInit()
        var runs = 0

        assertFailsWith<IllegalStateException> {
            gate.once {
                runs++
                error("boom")
            }
        }
        // The flag is set before the block, deliberately: a failing fetch that
        // re-armed the guard would retry on every resize, which is the exact
        // request storm the guard exists to prevent.
        gate.once { runs++ }

        assertEquals(1, runs)
    }
}
