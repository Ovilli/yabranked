package dev.yabranked.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scrollbar's arithmetic. The draw needs a game; mapping a position on the
 * track onto a scroll range does not, and that is where a scrollbar is wrong in
 * ways nobody notices until the list is long.
 */
class ScrollbarTest {

    /** A 100px bar at x=200,y=50 over 100 rows of which 10 are visible. */
    private fun bar(total: Int = 100, visible: Int = 10): Scrollbar =
        Scrollbar().also { it.layout(x = 200, y = 50, height = 100, total = total, visible = visible) }

    @Test
    fun `a click away from the bar is not the bar's business`() {
        val bar = bar()
        assertNull(bar.clicked(100.0, 60.0, scroll = 0), "a click left of the bar was swallowed")
        assertNull(bar.clicked(205.0, 500.0, scroll = 0), "a click below the bar was swallowed")
    }

    @Test
    fun `a click at the top of the track scrolls to the start`() {
        val bar = bar()
        assertEquals(0, bar.clicked(201.0, 50.0, scroll = 40))
    }

    @Test
    fun `a click at the bottom of the track scrolls to the end`() {
        val bar = bar()
        // 100 rows, 10 visible — the furthest you can scroll is row 90.
        assertEquals(90, bar.clicked(201.0, 149.0, scroll = 0))
    }

    @Test
    fun `dragging only answers while a drag is in progress`() {
        val bar = bar()
        assertNull(bar.dragged(100.0), "answered a drag that never started")
        bar.clicked(201.0, 60.0, scroll = 0)
        assertTrue(bar.dragging)
        assertEquals(0, bar.dragged(-500.0), "a drag above the track should clamp to the start")
        assertEquals(90, bar.dragged(5000.0), "a drag below the track should clamp to the end")
        bar.released()
        assertNull(bar.dragged(100.0), "kept dragging after release")
    }

    @Test
    fun `a list that fits has no scrollbar to hit`() {
        val bar = bar(total = 5, visible = 10)
        assertNull(bar.clicked(201.0, 60.0, scroll = 0))
        assertNull(bar.dragged(60.0))
    }

    @Test
    fun `grabbing the thumb does not make it jump`() {
        val bar = bar()
        // The thumb for scroll=0 starts at the top of the track; pressing a few
        // pixels into it must not re-centre it on the cursor.
        assertEquals(0, bar.clicked(201.0, 53.0, scroll = 0))
    }
}
