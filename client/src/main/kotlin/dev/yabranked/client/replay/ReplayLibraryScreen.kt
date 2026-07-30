package dev.yabranked.client.replay

import dev.yabranked.client.RankedState
import dev.yabranked.client.Sfx
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The recordings on this machine.
 *
 * Deliberately a *local* library rather than a view of the backend's list. A
 * recording is a large local file, and the question this screen answers — "what
 * can I watch right now" — is one the disk can answer when the network cannot: a
 * restarted backend, an expired retention window or no connection at all would
 * otherwise leave a player holding a hundred megabytes of match with no way in.
 * Match History reaches a replay too, but only for matches the backend still
 * remembers, and only one at a time.
 *
 * The size of each one is shown because it is the only screen where that number is
 * actionable: the cache is the player's disk, and this is where they can reclaim
 * it.
 */
class ReplayLibraryScreen(private val parent: Screen) : Screen(Component.literal("Replays")) {

    private var rows: List<ReplayCache.Cached> = emptyList()
    private var scroll = 0
    private var confirmDelete: String? = null

    /** Buttons are rebuilt per frame from the row layout, so hits are tracked here. */
    private class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val run: () -> Unit)

    private val hits = mutableListOf<Hit>()

    override fun init() {
        rows = ReplayViewer.cache.cached()
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
    }

    private val listTop get() = 58
    private val listBottom get() = height - 34

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        hits.clear()
        val centerX = width / 2

        g.centeredText(font, "§lREPLAYS", centerX, 20, Ui.ACCENT)
        val total = rows.sumOf { it.bytes }
        g.centeredText(
            font,
            if (rows.isEmpty()) "§7Nothing downloaded yet" else "§7${rows.size} on this machine · ${Ui.bytes(total)}",
            centerX, 34, Ui.TEXT_DIM,
        )

        if (rows.isEmpty()) {
            Ui.messageCard(
                g, font, centerX, height / 2 - 30,
                "§7Play a match, then open it from History to download its replay.",
                width = 300,
            )
            return
        }

        val listWidth = (width - 60).coerceIn(240, 420)
        val left = (width - listWidth) / 2

        g.enableScissor(0, listTop, width, listBottom)
        rows.forEachIndexed { index, entry ->
            drawRow(g, left, listWidth, index, entry, mouseX, mouseY)
        }
        g.disableScissor()
    }

    private fun drawRow(
        g: GuiGraphicsExtractor,
        left: Int,
        w: Int,
        index: Int,
        entry: ReplayCache.Cached,
        mouseX: Int,
        mouseY: Int,
    ) {
        val y = listTop + index * ROW - scroll
        if (y + ROW < listTop || y > listBottom) return
        Ui.row(g, left, y, w, ROW - 2)

        val meta = entry.meta
        val names = meta.streams.map { it.player.name }
        val title = if (names.size >= 2) "${names[0]} vs ${names[1]}" else names.firstOrNull() ?: meta.matchId.take(8)

        // Right-hand controls first: the label widths come from their text, so what
        // they leave over is what the title gets. Same rule as the friends list.
        var right = left + w - 6
        right = if (confirmDelete == meta.matchId) {
            button(g, right, y, "Sure?", Ui.LOSS, mouseX, mouseY) {
                confirmDelete = null
                ReplayViewer.cache.evict(meta.matchId)
                rows = ReplayViewer.cache.cached()
            }
        } else {
            button(g, right, y, "Delete", Ui.LOSS, mouseX, mouseY) {
                confirmDelete = meta.matchId
                Sfx.tick()
            }
        }
        val textRight = button(g, right, y, "Watch", Ui.WIN, mouseX, mouseY) { watch(entry) }

        val available = textRight - (left + 8)
        if (available > 0) {
            g.text(font, Ui.fit(font, title, available), left + 8, y + 4, Ui.WHITE)
            val when_ = DATE.format(Instant.ofEpochMilli(meta.recordedFrom).atZone(ZoneId.systemDefault()))
            val partial = if (meta.streams.any { it.truncated }) " §e· partial" else ""
            g.text(
                font,
                Ui.fit(
                    font,
                    "§7$when_ · ${Ui.duration(meta.durationSeconds)} · ${Ui.bytes(entry.bytes)}$partial",
                    available,
                ),
                left + 8, y + 16, Ui.TEXT_DIM,
            )
        }
    }

    private fun button(
        g: GuiGraphicsExtractor,
        right: Int,
        y: Int,
        label: String,
        color: Int,
        mouseX: Int,
        mouseY: Int,
        run: () -> Unit,
    ): Int {
        val w = font.width(label) + 10
        val h = 14
        val x = right - w
        val top = y + (ROW - 2 - h) / 2
        val hovered = mouseX >= x && mouseX < x + w && mouseY >= top && mouseY < top + h &&
            mouseY >= listTop && mouseY < listBottom
        g.fill(x, top, x + w, top + h, if (hovered) Ui.BUTTON_BG_LIT else Ui.BUTTON_BG)
        g.fill(x, top, x + w, top + 1, Ui.BUTTON_BORDER)
        g.fill(x, top + h - 1, x + w, top + h, Ui.BUTTON_BORDER)
        g.centeredText(font, label, x + w / 2, top + 3, if (hovered) Ui.WHITE else color)
        hits += Hit(x, top, w, h, run)
        return x - 3
    }

    private fun watch(entry: ReplayCache.Cached) {
        if (RankedState.activeMatch != null) {
            Sfx.tick()
            return
        }
        // Through the download screen even though nothing needs downloading: it is
        // where the "whose view" choice lives, and it resolves entirely from the
        // cache when the streams are already the length the index says.
        minecraft?.setScreenAndShow(
            ReplayDownloadScreen(this, entry.meta.matchId, titleOf(entry))
        )
    }

    private fun titleOf(entry: ReplayCache.Cached): String {
        val names = entry.meta.streams.map { it.player.name }
        return if (names.size >= 2) "${names[0]} vs ${names[1]}" else entry.meta.matchId
    }

    override fun mouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        if (event.y() >= listTop && event.y() < listBottom) {
            for (hit in hits) {
                if (event.x() >= hit.x && event.x() < hit.x + hit.w &&
                    event.y() >= hit.y && event.y() < hit.y + hit.h
                ) {
                    Sfx.select()
                    hit.run()
                    return true
                }
            }
        }
        return super.mouseClicked(event, doubled)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, dx: Double, dy: Double): Boolean {
        val maxScroll = (rows.size * ROW - (listBottom - listTop)).coerceAtLeast(0)
        scroll = (scroll - (dy * 12).toInt()).coerceIn(0, maxScroll)
        return true
    }

    override fun onClose() {
        minecraft?.setScreenAndShow(parent)
    }

    private companion object {
        const val ROW = 30
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")
    }
}
