package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.LeaderboardCategory
import dev.yabranked.proto.LeaderboardResponse
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * The per-category leaderboards.
 *
 * Rating is only one way to be good at this game, and only one mode's rating at
 * that. Each playable mode gets its own board (rated modes by that mode's
 * ladder, casual ones by wins), plus three cross-mode boards — endorsements,
 * time played, longest streak — so a player whose MMR is not the highest still
 * has somewhere to show up.
 *
 * The category list comes from the backend rather than being enumerated here,
 * so adding a mode server-side adds its board without a client update.
 */
class ModeLeaderboardScreen(
    private val parent: Screen?,
) : ScaledScreen(Component.literal("Leaderboards")) {

    private var categories: Loadable<List<LeaderboardCategory>> = Loadable.Loading

    /** The way back from a failed read; see [dev.yabranked.client.ui.RetryCard]. */
    private val retry = dev.yabranked.client.ui.RetryCard {
        // The categories decide which board is even asked for, so a retry starts
        // from whichever of the two is missing.
        if (categories is Loadable.Loaded) loadBoard() else loadCategories()
    }
    private var board: Loadable<LeaderboardResponse> = Loadable.Loading
    private var index = 0
    private var scroll = 0
    /** The list's scrollbar, draggable; see [dev.yabranked.client.ui.Scrollbar]. */
    private val bar = dev.yabranked.client.ui.Scrollbar()

    private var openedAt = 0L

    /** Bumped per load; a stale response is dropped rather than drawn. */
    private var loadId = 0

    private val listTop get() = 54
    private val listBottom get() = height - 56

    override fun layout() {
        val centerX = width / 2
        addRenderableWidget(
            RankedButton(centerX - 100, height - 52, 24, 20, Component.literal("◀")) { step(-1) }
        )
        addRenderableWidget(
            RankedButton(centerX + 76, height - 52, 24, 20, Component.literal("▶")) { step(+1) }
        )
        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        if (categories is Loadable.Loading) loadCategories()
    }

    private fun step(delta: Int) {
        val all = categories.valueOrNull ?: return
        if (all.isEmpty()) return
        index = ((index + delta) % all.size + all.size) % all.size
        scroll = 0
        Sfx.tick()
        loadBoard()
    }

    private fun loadCategories() {
        val backend = RankedState.backend ?: run {
            categories = Loadable.Failed("Not signed in")
            return
        }
        YabRankedClient.workers.execute {
            val fetched = backend.fetchLeaderboardCategories()
            minecraft.execute {
                categories = fetched.toLoadable()
                loadBoard()
            }
        }
    }

    private fun loadBoard() {
        val backend = RankedState.backend ?: return
        val category = categories.valueOrNull?.getOrNull(index) ?: return
        val id = ++loadId
        board = Loadable.Loading
        YabRankedClient.workers.execute {
            val fetched = backend.fetchLeaderboardPage(category.id, limit = 50)
            minecraft.execute {
                if (id != loadId) return@execute
                board = fetched.toLoadable()
            }
        }
    }

    override fun onMouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        val rows = board.valueOrNull?.rows?.size ?: 0
        val visible = (listBottom - listTop) / ROW
        scroll = (scroll - vAmount.toInt()).coerceIn(0, (rows - visible).coerceAtLeast(0))
        return true
    }

    override fun onMouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        bar.dragged(event.y())?.let { scroll = it / ROW; return true }
        return super.onMouseDragged(event, dragX, dragY)
    }

    override fun onMouseReleased(event: MouseButtonEvent): Boolean {
        bar.released()
        return super.onMouseReleased(event)
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        bar.clicked(event.x(), event.y(), scroll * ROW)?.let { scroll = it / ROW; return true }
        if (retry.clicked(event.x(), event.y())) return true
        val rows = board.valueOrNull?.rows ?: return super.onMouseClicked(event, doubled)
        if (event.y() >= listTop && event.y() < listBottom) {
            val row = rows.getOrNull(((event.y().toInt() - listTop) / ROW) + scroll)
            if (row != null) {
                Sfx.select()
                minecraft.setScreenAndShow(PlayerProfileScreen(this, row.player.uuid, row.player.name))
                return true
            }
        }
        return super.onMouseClicked(event, doubled)
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)

        val centerX = width / 2
        val category = categories.valueOrNull?.getOrNull(index)

        Ui.header(g, centerX - 110, 8, 220, 34)
        g.centeredText(font, "§lLEADERBOARDS", centerX, 13, Ui.ACCENT)
        g.centeredText(font, category?.displayName ?: "…", centerX, 25, Ui.WHITE)
        category?.let { g.centeredText(font, metricLabel(it), centerX, 34, Ui.TEXT_FAINT) }

        val listWidth = (width - 60).coerceIn(200, 380)
        val left = (width - listWidth) / 2

        val rows = when (val state = board) {
            is Loadable.Pending -> {
                // A failed board and a failed category list are the same dead end
                // to the player, and the retry has to redo whichever broke.
                // When the category list is what failed, the board is still
                // "Loading…" and always will be — say what actually broke.
                val message = (categories as? Loadable.Failed)?.message ?: state.message
                retry.draw(
                    g, font, centerX, listTop + 10, message,
                    retryable = state is Loadable.Failed || categories is Loadable.Failed,
                    mouseX = mouseX, mouseY = mouseY,
                )
                if (openedAt == 0L) openedAt = System.currentTimeMillis()
                Ui.fadeIn(g, width, height, openedAt)
                return
            }
            is Loadable.Loaded -> state.value.rows
        }
        if (rows.isEmpty()) {
            g.centeredText(font, "Nobody on this board yet.", centerX, listTop + 20, Ui.TEXT_DIM)
        }

        g.enableScissor(0, listTop, width, listBottom)
        val self = RankedState.profile?.uuid
        rows.drop(scroll).forEachIndexed { offset, row ->
            val y = listTop + offset * ROW
            if (y >= listBottom) return@forEachIndexed
            Ui.row(g, left, y, listWidth, ROW - 2)
            if (row.player.uuid == self) g.fill(left, y, left + listWidth, y + ROW - 2, Ui.SELECTION)
            else if (mouseY >= y && mouseY < y + ROW - 2 && mouseX >= left && mouseX < left + listWidth) {
                g.fill(left, y, left + listWidth, y + ROW - 2, Ui.HOVER)
            }

            val accent = row.tier?.let(Ui::tierColor) ?: Ui.TEXT_SOFT
            g.text(font, "#${row.rank}", left + 5, y + 5, if (row.rank <= 3) Ui.ACCENT else Ui.TEXT_FAINT)
            PlayerHeads.draw(g, left + 28, y + 3, 12, row.player.uuid, row.player.name, accent)
            if (RankedState.showFlags) Ui.flagIcon(g, left + 44, y + 4, row.player.country, 9)
            val nameX = left + 44 + if (RankedState.showFlags && row.player.country != null) 12 else 0
            g.text(font, Ui.fit(font, row.player.name, listWidth - 150), nameX, y + 4, Ui.WHITE)

            // The board's own metric on the right, pre-formatted by the server so
            // "3h 20m" and "1620" need no client-side unit guessing.
            Ui.textRight(g, font, row.value, left + listWidth - 6, y + 4, Ui.WHITE)
            val sub = buildList {
                row.tier?.let { add(it) }
                if (row.matchesPlayed > 0) add("${row.wins}W·${row.losses}L")
                if (row.currentStreak >= 2) add("${row.currentStreak} streak")
                add("E${row.endorsementLevel}")
            }.joinToString(" · ")
            Ui.textRight(g, font, Ui.fit(font, sub, listWidth - 60), left + listWidth - 6, y + 13, Ui.TEXT_FAINT)
        }
        g.disableScissor()

        bar.draw(g, width - 8, listTop, listBottom - listTop, rows.size * ROW, listBottom - listTop, scroll * ROW, mouseX, mouseY)

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun metricLabel(category: LeaderboardCategory): String = when (category.metric) {
        "endorsements" -> "Ranked by endorsements received"
        "playtime" -> "Ranked by time played this season"
        "streak" -> "Ranked by longest win streak this season"
        "wins" -> "Ranked by wins — this mode has no ladder"
        else -> "Ranked by this mode's own MMR"
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val ROW = 24
    }
}
