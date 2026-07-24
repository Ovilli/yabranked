package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

class LeaderboardScreen(
    private val parent: Screen?,
) : Screen(Component.literal("Leaderboard")) {

    private var entries: List<WireProfile>? = null
    private var error: String? = null

    // Season being viewed; -1 until resolved from the backend / profile.
    private var season: Int = RankedState.profile?.season ?: -1
    private var currentSeason: Int = RankedState.profile?.season ?: -1
    private var prevButton: RankedButton? = null
    private var nextButton: RankedButton? = null
    private var search: EditBox? = null
    private var sortButton: RankedButton? = null

    /** 0 = MMR (backend order), 1 = win rate, 2 = games played. */
    private var sortMode = 0

    /** First visible row index; driven by the mouse wheel. */
    private var scroll = 0

    /** Keyboard-selected row index into [rows]; -1 when nothing is selected. */
    private var selected = -1

    private fun games(p: WireProfile) = p.wins + p.losses + p.draws
    private fun winRate(p: WireProfile): Double {
        val decided = p.wins + p.losses
        return if (decided == 0) 0.0 else p.wins.toDouble() / decided
    }

    private fun sortLabel() = when (sortMode) {
        1 -> "Sort: Win%"
        2 -> "Sort: Games"
        else -> "Sort: MMR"
    }

    /** Rows after search + sort. Each keeps its true MMR rank (from the backend
     *  order) so the position number and podium medals stay meaningful even when
     *  the list is re-sorted by another column. */
    private fun rows(): List<Pair<Int, WireProfile>> {
        val list = entries ?: return emptyList()
        val q = search?.value?.trim().orEmpty()
        val base = list.mapIndexed { i, p -> (i + 1) to p }
            .filter { q.isEmpty() || it.second.name.contains(q, ignoreCase = true) }
        return when (sortMode) {
            1 -> base.sortedByDescending { winRate(it.second) }
            2 -> base.sortedByDescending { games(it.second) }
            else -> base
        }
    }

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    override fun init() {
        val centerX = width / 2
        prevButton = addRenderableWidget(
            RankedButton(centerX - 100, height - 52, 40, 20, Component.literal("◀")) { changeSeason(-1) }
        )
        nextButton = addRenderableWidget(
            RankedButton(centerX + 60, height - 52, 40, 20, Component.literal("▶")) { changeSeason(+1) }
        )
        addRenderableWidget(
            RankedButton(centerX - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        val left = centerX - WIDTH / 2
        // Search box (with a magnifier drawn in front of it) on the left; a sort
        // toggle on the right of the same strip.
        search = addRenderableWidget(
            EditBox(font, left + 14, 36, WIDTH - 14 - 74, 14, Component.literal("Search")).apply {
                setHint(Component.literal("Search players…"))
                setMaxLength(32)
                value = search?.value ?: ""
                setResponder { scroll = 0; selected = -1 }
            }
        )
        sortButton = addRenderableWidget(
            RankedButton(left + WIDTH - 70, 35, 70, 16, Component.literal(sortLabel())) {
                sortMode = (sortMode + 1) % 3
                scroll = 0
                selected = -1
                sortButton?.message = Component.literal(sortLabel())
            }
        )

        Sfx.open()

        val backend = RankedState.backend
        if (backend == null) {
            error = "Not signed in"
            return
        }
        load(backend)
    }

    private fun changeSeason(delta: Int) {
        val target = (season + delta).coerceIn(1, if (currentSeason > 0) currentSeason else season + delta)
        if (target == season) return
        season = target
        entries = null
        error = null
        scroll = 0
        RankedState.backend?.let { load(it) }
        updateNavState()
    }

    private fun load(backend: BackendClient) {
        val minecraft = this.minecraft
        YabRankedClient.workers.execute {
            if (currentSeason < 0) currentSeason = backend.fetchCurrentSeason()
            if (season < 0) season = currentSeason
            val fetched = backend.fetchLeaderboard(limit = 50, season = season)
            minecraft.execute {
                entries = fetched
                if (fetched.isEmpty()) error = "No rated players in season $season"
                updateNavState()
            }
        }
    }

    private fun updateNavState() {
        prevButton?.active = season > 1
        nextButton?.active = currentSeason < 0 || season < currentSeason
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        if (openedAt == 0L) openedAt = System.currentTimeMillis()

        val centerX = width / 2
        Ui.header(g, centerX - 110, 10, 220, 24)
        g.centeredText(font, "§lLEADERBOARD", centerX, 17, Ui.ACCENT)

        // Season label sits between the prev/next buttons.
        val seasonText = if (season > 0) "Season $season" else "Season …"
        val suffix = if (currentSeason > 0 && season == currentSeason) " §8(current)" else ""
        g.centeredText(font, "§7$seasonText$suffix", centerX, height - 46, Ui.TEXT_DIM)

        val left = centerX - WIDTH / 2
        val list = entries

        // magnifier in front of the search box
        Ui.icon(g, Ui.ICON_SEARCH, left + 2, 38, 10, Ui.TEXT_DIM)

        if (list == null || error != null) {
            Ui.messageCard(g, font, centerX, 66, error ?: "Loading…")
            Ui.fadeIn(g, width, height, openedAt)
            return
        }

        // column headers
        g.text(font, "PLAYER", left + 34, TOP - 12, Ui.TEXT_FAINT)
        Ui.textRight(g, font, "W/L", left + WIDTH - 62, TOP - 12, Ui.TEXT_FAINT)
        Ui.textRight(g, font, "MMR", left + WIDTH - 10, TOP - 12, Ui.TEXT_FAINT)

        val self = RankedState.profile?.uuid
        val rows = rows()
        if (rows.isEmpty()) {
            g.centeredText(font, "§7No players match your search", centerX, TOP + 8, Ui.TEXT_DIM)
            Ui.fadeIn(g, width, height, openedAt)
            return
        }
        val visible = ((height - 40 - TOP) / ROW_HEIGHT).coerceAtLeast(1)
        selected = selected.coerceIn(-1, rows.size - 1)
        scroll = scroll.coerceIn(0, (rows.size - visible).coerceAtLeast(0))
        for (i in 0 until visible) {
            val index = i + scroll
            if (index >= rows.size) break
            val (position, profile) = rows[index]
            val y = TOP + i * ROW_HEIGHT
            val hovered = (mouseX in left..(left + WIDTH) && mouseY in y until (y + ROW_HEIGHT - 1)) || index == selected
            drawRow(g, left, y, position, profile, isSelf = profile.uuid == self, hovered = hovered)
        }
        Ui.scrollbar(g, left + WIDTH + 2, TOP, visible * ROW_HEIGHT, rows.size, visible, scroll)
        Ui.fadeIn(g, width, height, openedAt)
    }

    private fun visibleRows() = ((height - 40 - TOP) / ROW_HEIGHT).coerceAtLeast(1)

    /** Scroll so the keyboard-selected row is on screen. */
    private fun ensureVisible() {
        val v = visibleRows()
        if (selected < scroll) scroll = selected
        else if (selected >= scroll + v) scroll = selected - v + 1
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        // Let the search box own keys while it is focused.
        if (search?.isFocused == true) return super.keyPressed(event)
        val rows = rows()
        when (event.key()) {
            GLFW.GLFW_KEY_DOWN -> {
                if (rows.isNotEmpty()) { selected = (selected + 1).coerceIn(0, rows.size - 1); ensureVisible() }
                return true
            }
            GLFW.GLFW_KEY_UP -> {
                if (rows.isNotEmpty()) { selected = (selected - 1).coerceAtLeast(0); ensureVisible() }
                return true
            }
            GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                rows.getOrNull(selected)?.let { (_, p) ->
                    Sfx.select()
                    minecraft.setScreenAndShow(PlayerProfileScreen(this, p.uuid, p.name))
                }
                return true
            }
        }
        return super.keyPressed(event)
    }

    private fun drawRow(g: GuiGraphicsExtractor, left: Int, y: Int, position: Int, profile: WireProfile, isSelf: Boolean, hovered: Boolean) {
        val tierColor = Ui.tierColor(profile.tier)

        // the viewer's own row is highlighted so it is findable at a glance
        Ui.row(g, left, y, WIDTH, ROW_HEIGHT - 1)
        if (isSelf) g.fill(left + 2, y + 1, left + WIDTH - 2, y + ROW_HEIGHT - 2, 0x2BFFC93C)
        // hover feedback signals the row is clickable (opens the player profile)
        if (hovered) g.fill(left + 2, y + 1, left + WIDTH - 2, y + ROW_HEIGHT - 2, 0x1AFFFFFF)
        Ui.accentBar(g, left, y, ROW_HEIGHT - 1, tierColor)

        val textY = y + 4

        // Top-3 get a tinted crown medal (gold/silver/bronze); everyone else a
        // plain rank number. The medal makes the podium pop at a glance.
        val medal = when (position) {
            1 -> 0xFFFFD24A.toInt() // gold
            2 -> 0xFFC8C8D0.toInt() // silver
            3 -> 0xFFCD7F3C.toInt() // bronze
            else -> 0
        }
        if (medal != 0) {
            Ui.icon(g, Ui.ICON_CROWN, left + 8, textY - 1, 10, medal)
        } else {
            g.text(font, "#$position", left + 8, textY, Ui.TEXT_DIM)
        }

        PlayerHeads.draw(g, left + 30, y + 2, 10, profile.uuid, profile.name, tierColor)
        // Clamp the name so it never runs into the rank badge / tier column.
        val name = Ui.fit(font, profile.name, (left + 122) - (left + 44))
        g.text(font, name, left + 44, textY, if (isSelf) Ui.ACCENT else Ui.WHITE)
        // Badge sized to the row so it does not bleed into the rows above/below.
        Ui.rankBadge(g, left + 124, y + 1, profile.tier, size = 12)
        g.text(font, profile.tier, left + 140, textY, tierColor)

        Ui.textRight(g, font, "${profile.wins} / ${profile.losses}", left + WIDTH - 62, textY, Ui.TEXT_DIM)
        Ui.textRight(g, font, "${profile.rating}", left + WIDTH - 10, textY, Ui.WHITE)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.button() != 0) return false
        val rows = rows()
        if (rows.isEmpty()) return false

        val left = width / 2 - WIDTH / 2
        val mouseX = event.x()
        val mouseY = event.y()
        if (mouseX < left || mouseX > left + WIDTH) return false
        val visible = ((height - 40 - TOP) / ROW_HEIGHT).coerceAtLeast(1)
        for (i in 0 until visible) {
            val index = i + scroll
            if (index >= rows.size) break
            val y = TOP + i * ROW_HEIGHT
            if (mouseY >= y && mouseY < y + ROW_HEIGHT - 1) {
                val p = rows[index].second
                Sfx.select()
                minecraft.setScreenAndShow(PlayerProfileScreen(this, p.uuid, p.name))
                return true
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, hAmount: Double, vAmount: Double): Boolean {
        val before = scroll
        if (vAmount > 0) scroll = (scroll - 1).coerceAtLeast(0) else if (vAmount < 0) scroll += 1
        if (scroll != before) Sfx.tick()
        return true
    }

    override fun onClose() {
        // null parent means the screen was opened by keybind mid-game:
        // fall back to vanilla behaviour, which returns to the game
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val WIDTH = 280
        const val TOP = 64 // leaves room for the search box (y=36..50) + column headers
        const val ROW_HEIGHT = 14
    }
}
