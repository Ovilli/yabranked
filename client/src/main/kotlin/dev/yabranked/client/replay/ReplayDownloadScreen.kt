package dev.yabranked.client.replay

import dev.yabranked.client.RankedState
import dev.yabranked.client.YabRankedClient
import dev.yabranked.client.ui.RankedButton
import dev.yabranked.client.ui.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor
import dev.yabranked.client.ui.ScaledScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Downloads a recording and then drops the player into it.
 *
 * There is a screen for this because there has to be: a recording is tens of
 * megabytes per player and playback cannot wait for the network in the middle of a
 * match — it feeds packets at the rate they were captured and seeks by replaying
 * from the start. So the whole thing comes down first, resumably, with the size
 * shown, and the player gets to decide whether they want to spend it.
 *
 * Whose perspective is offered as a choice rather than assumed, because a stream
 * only contains what its recipient was sent. Watching "the match" is always
 * watching from inside one player's field of view, and pretending otherwise would
 * mean silently picking one and calling it the truth.
 */
class ReplayDownloadScreen(
    private val parent: Screen,
    private val matchId: String,
    private val subtitle: String,
) : ScaledScreen(Component.literal("Replay")) {

    private var status = "Fetching the recording…"
    private var progress: ReplayCache.Progress? = null
    private var error: String? = null
    private var ready: ReplayCache.Result.Ready? = null
    private var perspective = 0
    private val started = AtomicBoolean(false)

    override fun layout() {
        val ready = this.ready
        if (ready != null) {
            // One button per participant: this is the "through whose eyes" choice,
            // and it is the last thing asked before the world opens.
            var y = height / 2 - 6
            for (stream in ready.meta.streams) {
                val label = stream.player.name + if (stream.truncated) " (partial)" else ""
                addRenderableWidget(
                    RankedButton(
                        width / 2 - 100, y, 200, 20,
                        Component.literal("See what $label saw"),
                        Ui.ICON_PLAY,
                    ) {
                        perspective = stream.index
                        play(ready)
                    }
                )
                y += 24
            }
        }
        addRenderableWidget(
            RankedButton(width / 2 - 100, height - 28, 200, 20, Component.literal("Back"), Ui.ICON_BACK) { onClose() }
        )
        if (started.compareAndSet(false, true)) download()
    }

    private fun download() {
        YabRankedClient.workers.execute {
            val client = RankedState.backend
            if (client == null) {
                // Not signed in is only fatal if the recording is not already here.
                // A downloaded replay is a local file; refusing to open one because
                // a *login* is missing would be refusing for no reason.
                val cached = ReplayViewer.cache.cachedMeta(matchId)
                minecraft?.execute {
                    if (cached != null) {
                        ready = ReplayCache.Result.Ready(cached, ReplayViewer.cache.dirOf(matchId))
                        status = "Ready"
                    } else {
                        error = "Not signed in — and this replay is not downloaded yet"
                    }
                    rebuild()
                }
                return@execute
            }
            val result = ReplayViewer.cache.download(client, matchId) { p ->
                // Straight onto the field: the render thread only reads it, and
                // hopping per chunk would queue thousands of tasks.
                progress = p
                status = "Downloading ${Ui.bytes(p.bytes)} of ${Ui.bytes(p.total)}"
            }
            minecraft?.execute {
                when (result) {
                    is ReplayCache.Result.Ready -> {
                        ready = result
                        status = "Ready"
                        rebuild()
                    }
                    is ReplayCache.Result.Failed -> {
                        error = result.message
                        rebuild()
                    }
                }
            }
        }
    }

    /** Re-run [init] so the perspective buttons appear once the download lands. */
    private fun rebuild() {
        clearWidgets()
        init()
    }

    private fun play(ready: ReplayCache.Result.Ready) {
        if (RankedState.activeMatch != null) {
            error = "You cannot watch a replay while you are in a match"
            rebuild()
            return
        }
        if (!ReplayViewer.open(ready.meta, ready.dir, perspective, parent)) {
            error = "That recording could not be opened"
            rebuild()
        }
    }

    override fun drawBackdrop(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Ui.drawBackground(g, width, height)
    }

    override fun drawContent(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.drawContent(g, mouseX, mouseY, partialTick)
        val centerX = width / 2

        g.centeredText(font, "§lREPLAY", centerX, 24, Ui.ACCENT)
        g.centeredText(font, "§7$subtitle", centerX, 38, Ui.TEXT_DIM)

        val message = error
        if (message != null) {
            Ui.messageCard(g, font, centerX, height / 2 - 20, "§c$message")
            return
        }

        val ready = this.ready
        if (ready == null) {
            g.centeredText(font, status, centerX, height / 2 - 30, Ui.WHITE)
            val p = progress
            if (p != null) {
                Ui.thinProgressBar(g, centerX - 100, height / 2 - 14, 200, p.fraction)
                g.centeredText(
                    font,
                    "§8stream ${p.stream + 1} of ${p.streams}",
                    centerX, height / 2 - 4, Ui.TEXT_DIM,
                )
            }
            return
        }

        val partial = ready.meta.streams.any { it.truncated }
        g.centeredText(font, "§7Whose view do you want to watch from?", centerX, height / 2 - 26, Ui.TEXT_DIM)
        // Said here rather than discovered in-world. A stream is what one client
        // was *sent*, and a server never sends a player their own position — so
        // the player you pick is the one player who is not in it.
        g.centeredText(
            font,
            "§8You see their chunks and the players they could see — not themselves.",
            centerX, height / 2 - 15, Ui.TEXT_DIM,
        )
        if (partial) {
            g.centeredText(
                font,
                "§eSome streams stop early — the match server did not finish uploading",
                centerX, height - 52, Ui.TEXT_DIM,
            )
        }
    }

    override fun onClose() {
        minecraft?.setScreenAndShow(parent)
    }
}
