package dev.yabranked.client.ui

import dev.yabranked.proto.PartyMember
import dev.yabranked.proto.PartyView
import dev.yabranked.proto.PlayerRef
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The party strip's geometry. Only the arithmetic is exercised — the draw call
 * needs a game — but the arithmetic is where the bug was: the plate was sized
 * from the slot row alone and clipped its own caption.
 */
class PartyBoxTest {

    /** Stand-in for a font: 6px a character, close enough to MC's default. */
    private val measure: (String) -> Int = { it.length * 6 }

    private fun party(members: Int, queued: Boolean = false) = PartyView(
        id = "p",
        leader = "0",
        members = (0 until members).map {
            PartyMember(player = PlayerRef(it.toString(), "Player$it"), leader = it == 0)
        },
        queued = queued,
    )

    private fun captionWidth(view: PartyView?, queued: Boolean = false): Int =
        measure(PartyBox.label(view)) +
            if (queued) measure(PartyBox.SEARCHING) else 0

    @Test
    fun `an empty strip is still wide enough for the word Party`() {
        // One 22px slot is narrower than the caption above it, which is exactly
        // the case that used to draw "Party" past the edge of its own plate.
        val width = PartyBox.width(null, measure)
        assertTrue(
            width >= captionWidth(null) + PartyBox.PADDING * 2,
            "plate ($width) must cover its caption plus padding",
        )
    }

    @Test
    fun `a counted caption fits too`() {
        val view = party(2)
        val width = PartyBox.width(view, measure)
        assertTrue(
            width >= captionWidth(view) + PartyBox.PADDING * 2,
            "plate ($width) must cover 'Party 2/8' plus padding",
        )
    }

    @Test
    fun `the searching tag widens the plate rather than overlapping the caption`() {
        val idle = party(2)
        val searching = party(2, queued = true)
        val quiet = PartyBox.width(idle, measure)
        val busy = PartyBox.width(searching, measure)

        assertTrue(busy > quiet, "the queued strip carries an extra tag and must be wider")
        assertTrue(
            busy >= captionWidth(searching, queued = true) + PartyBox.PADDING * 2,
            "plate ($busy) must cover caption and tag together",
        )
    }

    @Test
    fun `a full party is sized by its slots, not its caption`() {
        // Eight heads are far wider than "Party 8/8", so the slot row wins —
        // the fix must not make the strip grow past what it draws.
        val view = party(8)
        val slots = PartyBox.PADDING * 2 + 8 * PartyBox.SLOT + 7 * PartyBox.GAP
        assertTrue(PartyBox.width(view, measure) == slots)
    }
}
