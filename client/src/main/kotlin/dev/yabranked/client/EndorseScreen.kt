package dev.yabranked.client

import dev.yabranked.client.ui.PlayerHeads
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import dev.yabranked.proto.EndorsementCategory
import dev.yabranked.proto.EndorsementPrompt
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

/**
 * Endorse the teammates you just played with.
 *
 * Only teammates appear — the backend refuses anything else — and only once per
 * match, so the screen closes itself after a successful submit rather than
 * inviting a second pass. Selecting nobody and pressing skip is a first-class
 * outcome: an endorsement nobody meant is worse than none.
 */
class EndorseScreen(
    private val parent: Screen?,
    private val matchId: String,
) : ScaledScreen(Component.literal("Endorse teammates")) {

    private var prompt: Loadable<EndorsementPrompt> = Loadable.Loading
    private val chosen = linkedSetOf<String>()
    private var category = EndorsementCategory.TEAMWORK
    private var notice: String? = null
    private var submitting = false
    private var openedAt = 0L

    private val listTop get() = 56

    /** Above the notice line at `height - 64` and the two button rows under it,
     *  so a long roster can neither be drawn on nor click through them. */
    private val listBottom get() = height - 72

    override fun layout() {
        val centerX = width / 2
        addRenderableWidget(
            RankedButton(centerX - 120, height - 52, 240, 20, Component.literal("Reason: ${category.displayName}")) {
                val all = EndorsementCategory.entries
                category = all[(all.indexOf(category) + 1) % all.size]
                Sfx.tick()
                rebuildWidgets()
            }
        )
        addRenderableWidget(
            RankedButton(centerX - 120, height - 28, 118, 20, Component.literal("Skip"), Ui.ICON_BACK) { onClose() }
        )
        addRenderableWidget(
            RankedButton(centerX + 2, height - 28, 118, 20, Component.literal("Endorse (${chosen.size})"), Ui.ICON_FRIENDS) { submit() }
        ).active = chosen.isNotEmpty() && !submitting

        if (prompt is Loadable.Loading) load()
    }

    private fun load() {
        val backend = RankedState.backend ?: run {
            prompt = Loadable.Failed("Not signed in")
            return
        }
        YabRankedClient.workers.execute {
            val fetched = backend.fetchEndorsementPrompt(matchId)
            minecraft.execute {
                prompt = fetched?.let { Loadable.Loaded(it) }
                    ?: Loadable.Failed("Nothing to endorse for this match")
                rebuildWidgets()
            }
        }
    }

    private fun submit() {
        val backend = RankedState.backend ?: return
        if (chosen.isEmpty() || submitting) return
        submitting = true
        val targets = chosen.toList()
        val reason = category
        YabRankedClient.workers.execute {
            val error = backend.endorse(matchId, targets, reason)
            minecraft.execute {
                submitting = false
                if (error == null) {
                    Sfx.select()
                    RankedToast.show("Endorsed", "Thanks — your teammates were told.")
                    onClose()
                } else {
                    notice = error
                    Sfx.tick()
                    rebuildWidgets()
                }
            }
        }
    }

    override fun onMouseClicked(event: MouseButtonEvent, doubled: Boolean): Boolean {
        val teammates = prompt.valueOrNull?.teammates ?: return super.onMouseClicked(event, doubled)
        // Bounded at both ends: the row rectangles are unclipped, so without a
        // bottom edge a long roster answered clicks aimed at the Reason/Skip/
        // Endorse buttons — and this runs before super.mouseClicked.
        if (event.y() < listTop || event.y() >= listBottom) return super.onMouseClicked(event, doubled)
        val index = ((event.y().toInt() - listTop) / ROW)
        val target = teammates.getOrNull(index)
        if (target != null) {
            if (!chosen.remove(target.uuid)) chosen += target.uuid
            Sfx.tick()
            rebuildWidgets()
            return true
        }
        return super.onMouseClicked(event, doubled)
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height, blurred = true)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        val centerX = width / 2

        Ui.header(g, centerX - 110, 8, 220, 34)
        g.centeredText(font, "§lENDORSE", centerX, 13, Ui.ACCENT)
        g.centeredText(font, "Who played well with you?", centerX, 26, Ui.TEXT_DIM)

        val teammates = when (val state = prompt) {
            is Loadable.Pending -> {
                Ui.messageCard(g, font, centerX, listTop, state.message)
                if (openedAt == 0L) openedAt = System.currentTimeMillis()
                Ui.fadeIn(g, width, height, openedAt)
                return
            }
            is Loadable.Loaded -> state.value.teammates
        }

        val listWidth = (width - 80).coerceIn(180, 300)
        val left = (width - listWidth) / 2
        teammates.forEachIndexed { index, player ->
            val y = listTop + index * ROW
            // Matches the click bound, so nothing is drawn that cannot be picked.
            if (y + ROW - 2 > listBottom) return@forEachIndexed
            Ui.row(g, left, y, listWidth, ROW - 2)
            val picked = player.uuid in chosen
            if (picked) g.fill(left, y, left + listWidth, y + ROW - 2, Ui.SELECTION)
            else if (mouseY >= y && mouseY < y + ROW - 2 && mouseX >= left && mouseX < left + listWidth) {
                g.fill(left, y, left + listWidth, y + ROW - 2, Ui.HOVER)
            }
            PlayerHeads.draw(g, left + 6, y + 4, 16, player.uuid, player.name, if (picked) Ui.ACCENT else Ui.TEXT_SOFT)
            g.text(font, Ui.fit(font, player.name, listWidth - 50), left + 28, y + 7, if (picked) Ui.WHITE else Ui.TEXT_SOFT)
            Ui.textRight(g, font, if (picked) "✔" else "", left + listWidth - 8, y + 7, Ui.ACCENT)
        }

        // Server-worded refusal, so its length is not ours to assume.
        notice?.let { g.centeredText(font, Ui.fit(font, "§c$it", width - 20), centerX, height - 64, Ui.LOSS) }

        if (openedAt == 0L) openedAt = System.currentTimeMillis()
        Ui.fadeIn(g, width, height, openedAt)
    }

    override fun onClose() {
        if (parent != null) minecraft.setScreenAndShow(parent) else super.onClose()
    }

    private companion object {
        const val ROW = 26
    }
}
