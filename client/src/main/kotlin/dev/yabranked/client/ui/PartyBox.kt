package dev.yabranked.client.ui

import dev.yabranked.proto.PartyView
import dev.yabranked.proto.PresenceState
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The party strip shown in the ranked menus: one player head per member, the
 * leader crowned, and a "+" slot to invite someone else.
 *
 * Drawn rather than built from widgets because it has to sit on top of a screen
 * whose widget layout is rebuilt constantly, and because the only interactive
 * regions are the slots themselves — [slotAt] turns a click back into an index
 * so the screen can decide what a click on a member means.
 */
object PartyBox {

    const val SLOT = 22
    const val GAP = 3
    const val PADDING = 5
    const val HEIGHT = SLOT + PADDING * 2 + 10

    /** Right-aligned tag on the caption row while the party is in the queue. */
    const val SEARCHING = "searching"

    /** Minimum space kept between the caption and the "searching" tag. */
    private const val LABEL_GAP = 6

    /** Slot count: every member plus the invite slot, unless the party is full. */
    fun slotCount(party: PartyView?): Int {
        val members = party?.members?.size ?: 0
        val full = party != null && members >= party.maxSize
        return members + if (full) 0 else 1
    }

    /** Just the slot row's width; the panel is at least this wide. */
    private fun slotsWidth(party: PartyView?): Int {
        val slots = slotCount(party)
        return PADDING * 2 + slots * SLOT + (slots - 1).coerceAtLeast(0) * GAP
    }

    /** The caption above the slots, which is what the panel has to fit around. */
    fun label(party: PartyView?): String =
        if (party == null) "Party" else "Party ${party.members.size}/${party.maxSize}"

    /**
     * Panel width.
     *
     * The slot row alone is not enough: an empty party is one 22px slot, and the
     * word "Party" is wider than that, so the plate used to stop halfway through
     * its own caption. The caption — plus the "searching" tag when the party is
     * queued — is measured here and the panel takes whichever is wider.
     *
     * Measurement comes in as [textWidth] rather than a `Font` so the arithmetic
     * is testable without a running game; [width] below is the real call.
     */
    fun width(party: PartyView?, textWidth: (String) -> Int): Int {
        val caption = textWidth(label(party)) +
            if (party?.queued == true) LABEL_GAP + textWidth(SEARCHING) else 0
        return maxOf(slotsWidth(party), PADDING * 2 + caption)
    }

    fun width(font: Font, party: PartyView?): Int = width(party) { font.width(it) }

    /**
     * Which slot [mouseX]/[mouseY] is over: an index into the member list, or
     * [INVITE_SLOT] for the "+", or null when the cursor is elsewhere.
     */
    fun slotAt(party: PartyView?, x: Int, y: Int, mouseX: Int, mouseY: Int): Int? {
        val top = y + PADDING + 10
        if (mouseY < top || mouseY >= top + SLOT) return null
        val members = party?.members?.size ?: 0
        for (index in 0 until slotCount(party)) {
            val left = x + PADDING + index * (SLOT + GAP)
            if (mouseX >= left && mouseX < left + SLOT) {
                return if (index < members) index else INVITE_SLOT
            }
        }
        return null
    }

    /** Sentinel returned by [slotAt] for the "+" slot. */
    const val INVITE_SLOT = -1

    fun draw(
        g: GuiGraphicsExtractor,
        font: Font,
        x: Int,
        y: Int,
        party: PartyView?,
        hovered: Int? = null,
    ) {
        val width = width(font, party)
        Ui.panel(g, x, y, width, HEIGHT)

        g.text(font, label(party), x + PADDING, y + 3, Ui.TEXT_FAINT)
        if (party?.queued == true) {
            Ui.textRight(g, font, SEARCHING, x + width - PADDING, y + 3, Ui.ACCENT)
        }

        val top = y + PADDING + 10
        val members = party?.members.orEmpty()
        members.forEachIndexed { index, member ->
            val left = x + PADDING + index * (SLOT + GAP)
            val accent = when {
                member.leader -> Ui.ACCENT
                member.presence == PresenceState.OFFLINE -> Ui.TEXT_FAINT
                else -> Ui.TEXT_SOFT
            }
            Ui.slot(g, left, top, SLOT)
            if (hovered == index) g.fill(left + 1, top + 1, left + SLOT - 1, top + SLOT - 1, Ui.HOVER)
            PlayerHeads.draw(g, left + 3, top + 3, SLOT - 6, member.player.uuid, member.player.name, accent)
            // The leader's crown is the only badge here: it is the one fact that
            // changes what the viewer is allowed to do.
            if (member.leader) Ui.icon(g, Ui.ICON_CROWN, left + SLOT - 8, top - 2, 8, Ui.ACCENT)
            if (member.ready) g.fill(left, top + SLOT - 2, left + SLOT, top + SLOT, Ui.WIN)
        }

        if (slotCount(party) > members.size) {
            val left = x + PADDING + members.size * (SLOT + GAP)
            Ui.slot(g, left, top, SLOT)
            if (hovered == INVITE_SLOT) g.fill(left + 1, top + 1, left + SLOT - 1, top + SLOT - 1, Ui.HOVER)
            val color = if (hovered == INVITE_SLOT) Ui.ACCENT else Ui.TEXT_FAINT
            // A drawn plus rather than a glyph: it stays centred and crisp at
            // every GUI scale, which a font "+" does not.
            val cx = left + SLOT / 2
            val cy = top + SLOT / 2
            g.fill(cx - 5, cy - 1, cx + 5, cy + 1, color)
            g.fill(cx - 1, cy - 5, cx + 1, cy + 5, color)
        }
    }
}
