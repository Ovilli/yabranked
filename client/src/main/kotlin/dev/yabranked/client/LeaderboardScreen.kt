package dev.yabranked.client

import dev.yabranked.proto.*

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.EditBox
import dev.yabranked.client.ui.RankedButton
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

class LeaderboardScreen(
    private val parent: Screen?,
) : Screen(Component.literal("Leaderboard")) {

    private var entries: Loadable<List<PlayerProfile>> = Loadable.Loading

    // Season being viewed; -1 until resolved from the backend / profile.
    private var season: Int = RankedState.profile?.season ?: -1
    private var currentSeason: Int = RankedState.profile?.season ?: -1
    private var prevButton: RankedButton? = null
    private var nextButton: RankedButton? = null
    private var search: EditBox? = null
    private var sortButton: RankedButton? = null
    private var filterButton: RankedButton? = null

    /** 0 = MMR (backend order), 1 = win rate, 2 = games played. */
    private var sortMode = 0

    /** First visible row index; driven by the mouse wheel. */
    private var scroll = 0

    /** Keyboard-selected row index into [rows]; -1 when nothing is selected. */
    private var selected = -1

    private fun games(p: PlayerProfile) = p.wins + p.losses + p.draws
    private fun winRate(p: PlayerProfile): Double {
        val decided = p.wins + p.losses
        return if (decided == 0) 0.0 else p.wins.toDouble() / decided
    }

    private fun sortLabel() = when (sortMode) {
        1 -> "Sort: Win%"
        2 -> "Sort: Games"
        else -> "Sort: MMR"
    }

    /** Tier filter cycle: null = all, else a metal band (matched by tier prefix). */
    private val tierCycle = listOf<String?>(null, "Netherite", "Diamond", "Emerald", "Gold", "Iron", "Coal")
    private var tierIndex = 0
    private fun tierFilter() = tierCycle[tierIndex]
    private fun filterLabel() = "Tier: ${tierFilter() ?: "All"}"

    /** Rows after search + tier filter + sort. Each keeps its true MMR rank (from
     *  the backend order) so the position number and podium medals stay meaningful
     *  even when the list is re-sorted or filtered. */
    private fun rows(): List<Pair<Int, PlayerProfile>> {
        val list = entries.valueOrNull ?: return emptyList()
        val q = search?.value?.trim().orEmpty()
        val tier = tierFilter()
        val base = list.mapIndexed { i, p -> (i + 1) to p }
            .filter { q.isEmpty() || it.second.name.contains(q, ignoreCase = true) }
            .filter { tier == null || it.second.tier.substringBefore(' ') == tier }
        return when (sortMode) {
            1 -> base.sortedByDescending { winRate(it.second) }
            2 -> base.sortedByDescending { games(it.second) }
            else -> base
        }
    }

    /** Select the viewer's own row and scroll it into view. */
    private fun jumpToMe() {
        val me = RankedState.profile?.uuid ?: return
        val idx = rows().indexOfFirst { it.second.uuid == me }
        if (idx >= 0) {
            selected = idx
            ensureVisible()
            Sfx.select()
        } else {
            Sfx.tick()
        }
    }

    /** Wall-clock at first render, so the screen fades in from black. */
    private var openedAt = 0L

    /** Bumped on every [load]. Non-zero means the ladder has already been asked
     *  for, so the re-init a window resize triggers reuses what we have instead
     *  of refetching 50 rows; a response whose id is stale is dropped rather
     *  than overwriting the season the player has since paged to. */
    private var loadId = 0

    private val opened = FirstInit()
    private val loaded = FirstInit()

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
        // Tier filter (left of the season nav) and jump-to-me (right of it).
        filterButton = addRenderableWidget(
            RankedButton(centerX - 150, height - 52, 48, 20, Component.literal(filterLabel())) {
                tierIndex = (tierIndex + 1) % tierCycle.size
                scroll = 0
                selected = -1
                filterButton?.message = Component.literal(filterLabel())
                Sfx.tick()
            }
        )
        addRenderableWidget(
            RankedButton(centerX + 102, height - 52, 48, 20, Component.literal("Me"), Ui.ICON_PLAY) { jumpToMe() }
        )
        // The overall MMR ladder stays this screen's subject; the per-mode and
        // non-rating boards live next door rather than turning this one into a
        // table that changes its columns under the player.
        addRenderableWidget(
            RankedButton(left + WIDTH - 70, height - 76, 70, 16, Component.literal("More boards"), Ui.ICON_LEADERBOARD) {
                minecraft.setScreenAndShow(ModeLeaderboardScreen(this))
            }
        )

        opened.once { Sfx.open() }

        val backend = RankedState.backend
        if (backend == null) {
            entries = Loadable.Failed("Not signed in")
            return
        }
        // A window resize re-runs init() on this same instance; the ladder we
        // already have is still good, so don't pull 50 rows again. Retrying a
        // failed load here looked reasonable but fired once per frame of a
        // resize drag — the season buttons are the deliberate way back.
        loaded.once { load(backend) }
    }

    private fun changeSeason(delta: Int) {
        val target = (season + delta).coerceIn(1, if (currentSeason > 0) currentSeason else season + delta)
        if (target == season) return
        season = target
        scroll = 0
        RankedState.backend?.let { load(it) }
        updateNavState()
    }

    /**
     * Fetch the ladder for the season the player is looking at.
     *
     * The two season fields are read by the render thread on every frame, so the
     * worker reads copies taken here and hands its answers back through
     * [net.minecraft.client.Minecraft.execute] rather than assigning them itself.
     */
    private fun load(backend: BackendClient) {
        val minecraft = this.minecraft
        val id = ++loadId
        entries = Loadable.Loading
        val requested = season
        val knownCurrent = currentSeason
        YabRankedClient.workers.execute {
            // A negative season means "not resolved yet" — ask which one is live.
            val current = if (knownCurrent < 0) backend.fetchCurrentSeason() else knownCurrent
            val target = if (requested < 0) current else requested
            val fetched = backend.fetchLeaderboard(limit = 50, season = target)
            minecraft.execute {
                if (id != loadId) return@execute
                currentSeason = current
                season = target
                entries = fetched.toLoadable()
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

        // magnifier in front of the search box
        Ui.icon(g, Ui.ICON_SEARCH, left + 2, 38, 10, Ui.TEXT_DIM)

        val placeholder = entries.placeholder("No rated players in season $season")
        if (placeholder != null) {
            Ui.messageCard(g, font, centerX, 66, placeholder)
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
        val visible = visibleRows()
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

    /** Rows that fit between the column headers and the bottom control block.
     *  The single source for the row count — render, hit-testing and keyboard
     *  scrolling all read it, so they can never disagree about which row is
     *  where. */
    private fun visibleRows() = ((height - BOTTOM_CONTROLS - TOP) / ROW_HEIGHT).coerceAtLeast(1)

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

    private fun drawRow(g: GuiGraphicsExtractor, left: Int, y: Int, position: Int, profile: PlayerProfile, isSelf: Boolean, hovered: Boolean) {
        val tierColor = Ui.tierColor(profile.tier)

        // the viewer's own row is highlighted so it is findable at a glance
        Ui.row(g, left, y, WIDTH, ROW_HEIGHT - 1)
        if (isSelf) g.fill(left + 2, y + 1, left + WIDTH - 2, y + ROW_HEIGHT - 2, Ui.SELECTION)
        // hover feedback signals the row is clickable (opens the player profile)
        if (hovered) g.fill(left + 2, y + 1, left + WIDTH - 2, y + ROW_HEIGHT - 2, Ui.HOVER)
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
        val visible = visibleRows()
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

        /**
         * Height of the control block pinned to the bottom edge: "More boards"
         * at `height - 76`, the tier/season/Me row at `height - 52` and Back at
         * `height - 28`. The list stops above all of it.
         *
         * It used to stop at `height - 40`, so the last four rows were drawn
         * over those buttons — and since a widget wins the click, pressing what
         * looked like a player opened the mode boards instead.
         */
        const val BOTTOM_CONTROLS = 80
    }
}
