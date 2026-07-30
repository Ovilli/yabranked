package dev.yabranked.client

import dev.yabranked.client.ui.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/**
 * The client's one notification stack, down the top-right corner.
 *
 * These lines used to be a yellow string painted at the bottom of whichever
 * screen happened to own them, which put them on top of the buttons and left
 * them there until something else replaced them. A notice is news, not part of
 * the layout: it sits out of the way, it expires, and it never covers anything
 * the player can click.
 *
 * Several can be live at once — inviting three people produces three answers —
 * so they stack in arrival order rather than replacing one another. Oldest is
 * at the top; when it expires the rest slide up into its place, which is why
 * the stack reads as a list and not as a single flickering line.
 *
 * **One stack, not two.** This used to be paired with a [RankedToast] that went
 * through vanilla's toast manager, and the two drew in the same corner without
 * knowing about each other: a party message and a friend request arriving
 * together rendered on top of one another because they happened to be different
 * *kinds* of notification. [RankedToast] now pushes here, so everything queues
 * in one column whatever raised it, and the stack is drawn from one place
 * ([YabRankedClient]) on every screen and on the in-world HUD rather than by
 * each screen for itself.
 *
 * Render-thread only.
 */
object RankedNotice {

    private const val HOLD_MS = 4200L
    private const val FADE_MS = 220L

    /** How many are shown at once. Beyond this the oldest is retired early. */
    private const val MAX_SHOWN = 4

    private const val MARGIN = 6
    private const val HEIGHT = 28
    private const val GAP = 3
    private const val MIN_WIDTH = 120
    private const val MAX_WIDTH = 200

    /** Side of the square dismiss button in the notice's top-right corner. */
    private const val CLOSE = 9

    /** Pixels a slot slides per frame when the stack closes up above it. */
    private const val SLIDE_PER_MS = 0.12f

    /**
     * How long a screen's draw suppresses the in-world one. Long enough to
     * cover a slow frame, short enough that closing a screen brings the stack
     * back the same instant.
     */
    private const val SCREEN_OWNS_STACK_MS = 200L

    class Notice(val title: String, val body: String, val accent: Int) {
        var startedAt = 0L

        /** Where this notice is currently drawn, chased toward its slot's y. */
        var drawnY = -1f

        /**
         * Dismiss-button rectangle from the last frame, or null if it was not
         * drawn. Recorded rather than recomputed at click time because the stack
         * slides: the box the player aimed at is the one that was on screen.
         */
        var closeBox: IntArray? = null
    }

    private val live = ArrayDeque<Notice>()
    private var lastFrameAt = 0L

    /** Wall-clock of the last [draw] made on behalf of a screen; see [drawInWorld]. */
    private var lastScreenDrawAt = 0L

    /** Whether the cursor is over a dismiss button, so the caller can react. */
    private var hoveredClose: Notice? = null

    fun info(body: String, title: String = "Party") = push(Notice(title, body, Ui.ACCENT))

    fun error(body: String, title: String = "Party") = push(Notice(title, body, Ui.LOSS))

    /**
     * The general entry point, used by [RankedToast].
     *
     * @param narrate speak the notice as well as drawing it. A notice is by
     *   definition news the player did not ask for, so it is exactly the thing a
     *   narrator user misses; the opt-out exists for the cases where something
     *   louder has already said the same sentence.
     */
    fun show(title: String, body: String, accent: Int = Ui.ACCENT, narrate: Boolean = true) {
        push(Notice(title, body, accent))
        if (narrate) {
            Minecraft.getInstance()?.narrator?.saySystemNow(Component.literal("$title. $body"))
        }
    }

    fun push(notice: Notice) {
        // A reconnect loop reporting the same failure every second should read
        // as one notice, not as a column of identical ones. Refreshing the
        // existing one keeps it on screen instead of stacking a duplicate.
        live.firstOrNull { it.body == notice.body }?.let {
            it.startedAt = System.currentTimeMillis()
            return
        }
        notice.startedAt = System.currentTimeMillis()
        live.addLast(notice)
        // Retire from the top rather than refusing the new one: the newest
        // notice is the one the player is waiting on.
        while (live.size > MAX_SHOWN) live.removeFirst()
    }

    /** Drop everything, e.g. when a match starts and none of it applies. */
    fun clear() = live.clear()

    /** Whether anything is on screen; lets callers skip the input plumbing. */
    val isEmpty: Boolean get() = live.isEmpty()

    /**
     * Expire whatever has run out its time.
     *
     * Driven from the client tick rather than from [draw] so that a notice
     * pushed while no ranked screen is open still ages out — otherwise opening
     * the party screen an hour later would greet the player with the reason
     * their last invite failed.
     */
    fun tick() {
        if (live.isEmpty()) return
        val now = System.currentTimeMillis()
        // Not necessarily the front one: push() refreshes a repeat in place, so
        // an older entry can outlive a newer one.
        live.removeAll { now - it.startedAt >= HOLD_MS + FADE_MS }
    }

    /**
     * Handle a click at [mouseX]/[mouseY]; true when it dismissed a notice.
     *
     * Only the dismiss button consumes a click. The notice body deliberately
     * does not: it sits over whatever screen is open, and swallowing clicks that
     * merely landed under a notice would make the screen underneath feel broken.
     */
    fun clickedAt(mouseX: Double, mouseY: Double): Boolean {
        val hit = live.firstOrNull { notice ->
            val box = notice.closeBox ?: return@firstOrNull false
            mouseX >= box[0] && mouseX < box[2] && mouseY >= box[1] && mouseY < box[3]
        } ?: return false
        live.remove(hit)
        Sfx.tick()
        return true
    }

    /**
     * Draw the stack in the top-right corner of [screenWidth], starting [top]
     * pixels down — the queue badge lives in the same corner, so the caller
     * passes the height it has already used.
     *
     * The right edge is the one strip these screens keep clear: the title plate
     * is centred, the party strip is top-left, and every control is in the
     * centre column. Nothing here is ever drawn over something clickable.
     */
    fun draw(g: GuiGraphicsExtractor, font: Font, screenWidth: Int, top: Int = MARGIN, mouseX: Int = -1, mouseY: Int = -1) {
        lastScreenDrawAt = System.currentTimeMillis()
        drawStack(g, font, screenWidth, top, mouseX, mouseY)
    }

    /**
     * The in-world draw, from the HUD.
     *
     * The HUD still renders underneath an open screen, and the screen hook is
     * already drawing the stack there — painting it twice doubles its alpha and
     * runs its slide at double speed. This mapping exposes no public getter for
     * the current screen, so "was a screen drawing it a moment ago" stands in
     * for "is a screen open": the two paths run in the same frame, so the window
     * only has to outlast one.
     */
    fun drawInWorld(g: GuiGraphicsExtractor, font: Font, screenWidth: Int) {
        if (System.currentTimeMillis() - lastScreenDrawAt < SCREEN_OWNS_STACK_MS) return
        drawStack(g, font, screenWidth, MARGIN, -1, -1)
    }

    private fun drawStack(
        g: GuiGraphicsExtractor,
        font: Font,
        screenWidth: Int,
        top: Int,
        mouseX: Int,
        mouseY: Int,
    ) {
        if (live.isEmpty()) {
            lastFrameAt = 0L
            hoveredClose = null
            return
        }
        val now = System.currentTimeMillis()
        // Frame delta, so the slide runs at the same speed whatever the frame
        // rate. Clamped because the first frame after a pause is arbitrarily long.
        val delta = if (lastFrameAt == 0L) 0f else (now - lastFrameAt).coerceIn(0L, 100L).toFloat()
        lastFrameAt = now
        hoveredClose = null

        live.forEachIndexed { index, notice ->
            val elapsed = now - notice.startedAt
            val fade = when {
                elapsed < FADE_MS -> elapsed.toFloat() / FADE_MS
                elapsed > HOLD_MS -> 1f - ((elapsed - HOLD_MS).toFloat() / FADE_MS)
                else -> 1f
            }.coerceIn(0f, 1f)
            if (fade <= 0f) {
                notice.closeBox = null
                return@forEachIndexed
            }
            val a = (fade * 255).toInt().coerceIn(0, 255)

            val maxWidth = (screenWidth / 2).coerceIn(MIN_WIDTH, MAX_WIDTH)
            // The title shares its row with the dismiss button, so it is fitted
            // to the shorter run rather than the full plate.
            val body = Ui.fit(font, notice.body, maxWidth - 16)
            val title = Ui.fit(font, notice.title, maxWidth - 16 - CLOSE - 2)
            val w = (maxOf(font.width(title) + CLOSE + 2, font.width(body)) + 20)
                .coerceIn(MIN_WIDTH.coerceAtMost(screenWidth - 8), maxWidth)

            val slotY = (top + index * (HEIGHT + GAP)).toFloat()
            // A new notice starts at its slot; an existing one walks to the slot
            // it inherited when the notice above it expired.
            notice.drawnY = when {
                notice.drawnY < 0f -> slotY
                else -> approach(notice.drawnY, slotY, delta * SLIDE_PER_MS)
            }

            // Slides in from off the right edge as it fades, so it reads as
            // arriving rather than as text appearing in the corner.
            val x = screenWidth - w - MARGIN + ((1f - fade) * 8).toInt()
            val y = notice.drawnY.toInt()

            g.fill(x, y, x + w, y + HEIGHT, Ui.alpha(Ui.PANEL_BORDER, a))
            g.fill(x + 1, y + 1, x + w - 1, y + HEIGHT - 1, Ui.alpha(Ui.PANEL_BG, a))
            g.fill(x, y, x + 2, y + HEIGHT, Ui.alpha(notice.accent, a))
            g.text(font, title, x + 8, y + 5, Ui.alpha(notice.accent, a))
            g.text(font, body, x + 8, y + 16, Ui.alpha(Ui.WHITE, a))

            // Dismiss button. Notices expire on their own, but "wait four
            // seconds" is not an answer for someone who wants the corner of
            // their screen back now.
            val cx = x + w - CLOSE - 3
            val cy = y + 3
            notice.closeBox = intArrayOf(cx, cy, cx + CLOSE, cy + CLOSE)
            val over = mouseX >= cx && mouseX < cx + CLOSE && mouseY >= cy && mouseY < cy + CLOSE
            if (over) {
                hoveredClose = notice
                g.fill(cx, cy, cx + CLOSE, cy + CLOSE, Ui.alpha(Ui.HOVER, a))
            }
            Ui.icon(g, Ui.ICON_CLOSE, cx, cy, CLOSE, Ui.alpha(if (over) Ui.WHITE else Ui.TEXT_FAINT, a))
        }
    }

    private fun approach(from: Float, to: Float, step: Float): Float =
        if (from < to) minOf(to, from + step) else maxOf(to, from - step)
}
