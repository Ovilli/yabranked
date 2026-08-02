package dev.yabranked.client

import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.MatchCategory
import dev.yabranked.proto.MatchFormat
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Mode picker.
 *
 * Replaces the old single "Format: …" button that had to be clicked through
 * every mode in turn. Modes are now grouped the way a player thinks about them
 * — solo ranked, team ranked, casual, party — and each card states the three
 * things the choice actually turns on: how many players a side, whether it is
 * rated, and what the win condition is.
 *
 * Party-only modes are shown but only selectable from inside a party, and team
 * modes say plainly whether the player will be auto-filled with strangers or
 * queue as their own party.
 */
class ModeSelectScreen(
    private val parent: Screen?,
) : ScaledScreen(Component.literal("Choose a mode")) {

    private data class Section(val title: String, val subtitle: String, val formats: List<MatchFormat>)

    private val sections: List<Section> by lazy {
        val playable = MatchFormat.entries.filter { it.playable }
        listOf(
            Section(
                "Ranked · Solo",
                "One player a side. Moves your main MMR.",
                playable.filter { it.ranked && it.category == MatchCategory.SOLO },
            ),
            Section(
                "Ranked · Teams",
                "Auto-filled from the queue by MMR, or queued as your party.",
                playable.filter { it.ranked && it.category == MatchCategory.TEAM },
            ),
            Section(
                "Casual",
                "Nothing at stake. Playtime still counts toward your profile.",
                playable.filter { !it.ranked && !it.partyOnly },
            ),
            Section(
                "Party",
                "Your party against itself. Only startable from a party.",
                playable.filter { it.partyOnly },
            ),
        ).filter { it.formats.isNotEmpty() }
    }

    /** Flattened card order, for keyboard navigation. */
    private val order: List<MatchFormat> by lazy { sections.flatMap { it.formats } }

    private var selected = RankedState.selectedFormat
    private var scroll = 0
    /** The list's scrollbar, draggable; see [dev.yabranked.client.ui.Scrollbar]. */
    private val bar = dev.yabranked.client.ui.Scrollbar()

    private var openedAt = 0L

    /**
     * When this screen was constructed, for the input guard in [mouseClicked].
     *
     * Distinct from [openedAt], which is set on the first *render* and drives
     * the fade — this one has to exist before any input can arrive.
     */
    private val createdAt = System.currentTimeMillis()

    /** Content height, measured during layout so the scroll clamp is exact. */
    private var contentHeight = 0

    private val listTop get() = 44
    private val listBottom get() = height - 34

    override fun layout() {
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 98, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        addRenderableWidget(
            RankedButton(width / 2 + 2, height - 28, 98, 20, Component.literal("Choose"), Ui.ICON_PLAY) { confirm() }
        )
    }

    /** Whether the player may actually queue for [format] right now. */
    private fun blockedReason(format: MatchFormat): String? = when {
        format.partyOnly && RankedState.party == null -> "Create a party first"
        format.partyOnly && !RankedState.isPartyLeader -> "Only the party leader picks the mode"
        else -> null
    }

    private fun confirm() {
        val reason = blockedReason(selected)
        if (reason != null) {
            RankedState.statusMessage = "§e$reason"
            Sfx.tick()
            return
        }
        RankedState.selectedFormat = selected
        // A party's mode is the leader's setting, not a local one: pushing it
        // here is what makes every member's screen agree with the leader's.
        if (RankedState.isPartyLeader) {
            RankedState.party?.options?.let { RankedParty.setOptions(it.copy(format = selected)) }
        }
        Sfx.select()
        onClose()
    }

    // --- layout ---

    /** Runs [row] for every card with its screen rectangle, and returns the total height. */
    private inline fun eachCard(block: (format: MatchFormat, x: Int, y: Int, w: Int, h: Int) -> Unit): Int {
        val cardWidth = (width - 60).coerceIn(180, 420)
        val left = (width - cardWidth) / 2
        var y = listTop - scroll
        for (section in sections) {
            y += SECTION_HEADER
            for (format in section.formats) {
                block(format, left, y, cardWidth, CARD)
                y += CARD + CARD_GAP
            }
            y += SECTION_GAP
        }
        return y + scroll - listTop
    }

    private fun maxScroll(): Int = (contentHeight - (listBottom - listTop)).coerceAtLeast(0)

    override fun onMouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        scroll = (scroll - (vAmount * SCROLL_STEP).toInt()).coerceIn(0, maxScroll())
        return true
    }

    override fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        bar.dragged(event.y())?.let { scroll = it; return true }
        return super.onMouseDragged(event, dragX, dragY)
    }

    override fun onMouseReleased(event: MouseButtonEvent): Boolean {
        bar.released()
        return super.onMouseReleased(event)
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        // The click that opened this screen can be followed immediately by a
        // second one from the same press-and-hold, at a cursor position chosen
        // for the *previous* screen's button — which lands on whatever card
        // happens to be under it here. Ignore input until the player has had a
        // chance to see what they are clicking. The scrollbar is behind this
        // guard too: a stray press that scrolls the list is milder than one that
        // picks a mode, but it is still a press the player did not make.
        if (System.currentTimeMillis() - createdAt < INPUT_GUARD_MS) return true

        bar.clicked(event.x(), event.y(), scroll)?.let { scroll = it; return true }

        // Only what is actually visible is clickable. The cards are scissored to
        // the viewport but their rectangles are not, so a card scrolled past the
        // bottom edge still answered clicks — and since this runs before
        // super.mouseClicked, it swallowed presses of Back and Choose.
        if (event.y() < listTop || event.y() >= listBottom) return super.onMouseClicked(event, doubled)

        var hit: MatchFormat? = null
        eachCard { format, x, y, w, h ->
            if (event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + h) hit = format
        }
        val format = hit
        if (format != null) {
            // Selecting and confirming are separate actions. A single click on
            // the already-selected card used to start the match outright, so
            // arriving with that card under the cursor picked a mode nobody
            // chose. Confirming is Choose, Enter, or a deliberate double click.
            if (doubled && selected == format) confirm()
            else {
                selected = format
                Sfx.tick()
            }
            return true
        }
        return super.onMouseClicked(event, doubled)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val index = order.indexOf(selected)
        when (event.key) {
            GLFW.GLFW_KEY_DOWN -> {
                selected = order[(index + 1).coerceAtMost(order.size - 1)]
                ensureVisible()
                return true
            }
            GLFW.GLFW_KEY_UP -> {
                selected = order[(index - 1).coerceAtLeast(0)]
                ensureVisible()
                return true
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                confirm()
                return true
            }
        }
        return super.keyPressed(event)
    }

    /** Scrolls the selected card fully into the list viewport. */
    private fun ensureVisible() {
        var top = 0
        var bottom = 0
        eachCard { format, _, y, _, h ->
            if (format == selected) {
                top = y + scroll
                bottom = y + h + scroll
            }
        }
        if (top < listTop + scroll) scroll = (top - listTop).coerceAtLeast(0)
        if (bottom > listBottom + scroll) scroll = (bottom - listBottom).coerceAtMost(maxScroll())
    }

    // --- rendering ---

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        Ui.title(g, font, centerX, "§lCHOOSE A MODE")

        g.enableScissor(0, listTop, width, listBottom)
        val cardWidth = (width - 60).coerceIn(180, 420)
        val left = (width - cardWidth) / 2
        var y = listTop - scroll
        for (section in sections) {
            g.text(font, "§l${section.title}", left, y + 2, Ui.WHITE)
            Ui.textRight(g, font, Ui.fit(font, section.subtitle, cardWidth - font.width(section.title) - 20), left + cardWidth, y + 2, Ui.TEXT_FAINT)
            y += SECTION_HEADER
            for (format in section.formats) {
                drawCard(g, format, left, y, cardWidth, mouseX, mouseY)
                y += CARD + CARD_GAP
            }
            y += SECTION_GAP
        }
        g.disableScissor()
        contentHeight = y + scroll - listTop

        bar.draw(g, width - 8, listTop, listBottom - listTop, contentHeight, listBottom - listTop, scroll, mouseX, mouseY)

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun drawCard(
        g: GuiGraphicsExtractor,
        format: MatchFormat,
        x: Int,
        y: Int,
        w: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        val hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + CARD &&
            mouseY >= listTop && mouseY < listBottom
        val chosen = format == selected
        val blocked = blockedReason(format)

        Ui.row(g, x, y, w, CARD)
        if (chosen) g.fill(x, y, x + w, y + CARD, Ui.SELECTION)
        else if (hovered) g.fill(x, y, x + w, y + CARD, Ui.HOVER)
        // Rated modes carry the accent; casual and party ones stay grey, so the
        // one thing that decides whether a loss hurts is visible at a glance.
        Ui.accentBar(g, x, y, CARD, if (format.ranked) Ui.ACCENT else Ui.TEXT_FAINT)

        val textLeft = x + 8
        val textRight = x + w - 8
        val nameColor = if (blocked != null) Ui.TEXT_FAINT else Ui.WHITE
        g.text(font, format.displayName, textLeft, y + 5, nameColor)

        val shape = if (format.teamSize == 1 && format.teamCount == 2) "1v1"
        else if (format.partyOnly) "party"
        else "${format.teamSize}v${format.teamSize}"
        Ui.chip(g, font, textRight - font.width(shape) / 2 - 6, y + 3, shape, Ui.SLOT_BG, Ui.TEXT_SOFT)

        val rules = format.rules
        val objective = when (rules.goalType) {
            "lines" -> if (rules.goalCount == 1) "first line" else "${rules.goalCount} lines"
            "items" -> "${rules.goalCount} items"
            else -> rules.goalType
        }
        val detail = buildList {
            add(if (format.ranked) "rated" else "unrated")
            add(objective)
            if (rules.lockout) add("lockout")
            if (rules.hiddenItems) add("hidden items")
            add("${rules.timeLimitMinutes}m")
        }.joinToString(" · ")
        g.text(font, Ui.fit(font, detail, w - 16), textLeft, y + 17, Ui.TEXT_DIM)

        val note = blocked
            ?: when {
                format.partyOnly -> "Your party plays this against itself"
                format.teamSize > 1 && RankedState.party != null ->
                    "Your party queues as one side"
                format.teamSize > 1 -> "Teammates are filled in by MMR"
                else -> null
            }
        if (note != null) {
            g.text(
                font,
                Ui.fit(font, note, w - 16),
                textLeft,
                y + 28,
                if (blocked != null) Ui.LOSS else Ui.TEXT_FAINT,
            )
        }
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val CARD = 40
        const val CARD_GAP = 3
        const val SECTION_HEADER = 16
        const val SECTION_GAP = 8
        const val SCROLL_STEP = 18

        /** Long enough to outlast a double-click, short enough not to be felt. */
        const val INPUT_GUARD_MS = 250L
    }
}
