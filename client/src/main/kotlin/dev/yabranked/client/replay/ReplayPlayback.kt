package dev.yabranked.client.replay

import com.mojang.authlib.GameProfile
import dev.yabranked.proto.MatchReplayMeta
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl
import net.minecraft.client.multiplayer.ClientRegistryLayer
import net.minecraft.client.multiplayer.CommonListenerCookie
import net.minecraft.client.multiplayer.LevelLoadTracker
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.configuration.ConfigurationProtocols
import net.minecraft.server.ServerLinks
import net.minecraft.world.flag.FeatureFlags
import net.minecraft.world.level.GameType
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

/**
 * Plays a recorded match back inside the game.
 *
 * This is the thing the replay system exists for. A recording is every clientbound
 * packet the match server sent a participant, so playing it back is not a
 * reconstruction: the client is fed the same bytes it would have been fed live, and
 * builds the same world out of them — the same terrain, the same chests, the same
 * players walking through it. Nothing is generated locally and nothing is
 * approximated.
 *
 * ## How
 *
 * A [Connection] is built over an [EmbeddedChannel] — a Netty pipeline with no
 * socket under it — configured exactly as a real client connection is, with a
 * [ClientConfigurationPacketListenerImpl] on the inbound side. Frames are then
 * written into the head of that pipeline in timestamp order, length-prefixed the
 * way the wire prefixes them. From the client's point of view a server is talking
 * to it. Vanilla does the rest: `handleConfigurationFinished` swaps the protocol to
 * play and builds the `ClientPacketListener`, which builds the `ClientLevel` and
 * the `LocalPlayer`, and the game is in a world.
 *
 * Serverbound packets the client sends in reply — keep-alives, movement, chat —
 * are written into the same channel and discarded. There is nobody there.
 *
 * ## Consequences worth knowing
 *
 * - **The recording starts before the match does.** The configuration phase carries
 *   the dynamic registries, without which not one play-phase packet decodes, so the
 *   capture begins at the handshake. [MatchReplayMeta.gameStartMillis] is where the
 *   playhead opens; the frames before it are the lobby.
 * - **Seeking backwards means starting again.** A packet stream is a sequence of
 *   deltas, not a set of snapshots: there is no way to un-break a block. Rewinding
 *   tears the world down and fast-forwards to the target without rendering, which
 *   is how every packet-based replay has ever done it and is why a seek backwards
 *   costs more than one forwards.
 * - **The viewer is a spectator in someone else's field of view.** A stream only
 *   contains what its recipient was sent — the chunks *they* had loaded, the
 *   entities in *their* tracking range — so watching is always watching from one
 *   player's perspective, and switching perspective is switching stream, which is
 *   a re-open. The camera itself is free: a player's own movement is never sent
 *   back to them, so nothing in the stream fights the viewer for the camera.
 */
class ReplayPlayback private constructor(
    val meta: MatchReplayMeta,
    /** Which stream's field of view is being watched. */
    val primaryIndex: Int,
    private val stream: ReplayStreamFile,
    private val parent: Screen?,
) {
    private val minecraft: Minecraft = Minecraft.getInstance()
    private val log = LoggerFactory.getLogger("yabranked-replay")

    private var connection: Connection? = null
    private var channel: EmbeddedChannel? = null

    /** Next frame to feed. */
    private var cursor = 0

    /** Playhead, in millis from the start of the recording. */
    var positionMillis: Int = 0
        private set

    var paused: Boolean = false
    var speed: Float = 1f
        set(value) {
            field = value.coerceIn(MIN_SPEED, MAX_SPEED)
        }

    /** True while a seek is feeding frames without waiting for the clock. */
    var seeking: Boolean = false
        private set

    @Volatile private var closed = false

    /** Wall-clock ms at which [positionMillis] was last synchronised. */
    private var anchorRealMs = 0L
    private var anchorPlayMs = 0

    /** Reused so feeding a frame does not allocate; grown to the largest seen. */
    private var buffer = ByteArray(1 shl 16)

    val endMillis: Int get() = stream.endMillis

    val isOver: Boolean get() = cursor >= stream.frameCount

    /**
     * Begin. Tears down whatever world the client is in first: playback replaces
     * the connection, and two of them at once is a client with two levels.
     */
    fun start() {
        val profile = meta.streams.firstOrNull { it.index == primaryIndex }?.player
        val uuid = runCatching { UUID.fromString(profile?.uuid) }.getOrNull() ?: UUID.randomUUID()
        val gameProfile = GameProfile(uuid, profile?.name ?: "Replay")

        val connection = Connection(PacketFlow.CLIENTBOUND)
        val channel = EmbeddedChannel()
        Connection.configureSerialization(channel.pipeline(), PacketFlow.CLIENTBOUND, false, null)
        connection.configurePacketHandler(channel.pipeline())
        // The channel is already registered by the time the handlers are in, and
        // Netty does not replay `channelActive` for late arrivals — so the
        // Connection would never learn its own channel without this.
        channel.pipeline().fireChannelActive()

        // Everything the client tries to say is dropped before it can be encoded.
        //
        // It does try, constantly and from the first frame: Fabric's attachment
        // sync answers a configuration custom payload, then come the brand, the
        // client information, keep-alives, movement. There is nobody to hear any
        // of it, and letting it reach an encoder is not merely wasteful, it is
        // fatal — the encoder installed by `configureSerialization` on a *client*
        // pipeline is a real `PacketEncoder` bound to the handshake protocol, and
        // it answers an unknown packet with an `EncoderException`, which
        // `Connection.exceptionCaught` treats as a connection fault and
        // disconnects on. The world was being torn down three frames in by the
        // client's own first reply.
        //
        // `setupOutboundProtocol` is *not* the fix and was tried: it writes an
        // `OutboundConfigurationTask` through the pipeline, and the only handler
        // that runs one is `UnconfiguredPipelineHandler$Outbound`, which
        // `configureSerialization` installs on the server side only. On a client
        // the task passes through every handler untouched and replaces nothing.
        channel.pipeline().addLast(SINK, OutboundSink())

        val listener = ClientConfigurationPacketListenerImpl(minecraft, connection, cookieFor(gameProfile))
        connection.setupInboundProtocol(ConfigurationProtocols.CLIENTBOUND, listener)

        this.connection = connection
        this.channel = channel
        cursor = 0
        positionMillis = 0
        resync()
        log.info("replaying match {} from stream {}", meta.matchId, primaryIndex)
    }

    /**
     * A cookie for a connection that has no server behind it.
     *
     * `serverData` is null and the brand is empty because there is no server to
     * describe; the registries start as the client's own defaults and are replaced
     * by the recorded registry-sync packets, which is exactly what happens on a
     * real connection.
     */
    private fun cookieFor(profile: GameProfile) = CommonListenerCookie(
        LevelLoadTracker(),
        profile,
        minecraft.telemetryManager.createWorldSessionManager(false, Duration.ZERO, null, UUID.randomUUID()),
        ClientRegistryLayer.createRegistryAccess().compositeAccess(),
        FeatureFlags.DEFAULT_FLAGS,
        "replay",
        null,
        parent,
        emptyMap(),
        null,
        emptyMap(),
        ServerLinks.EMPTY,
        emptyMap(),
        false,
    )

    /**
     * Advance the playhead and feed everything that has come due.
     *
     * Called from the client tick, on the render thread, which is deliberate:
     * `PacketUtils.ensureRunningOnSameThread` then runs each handler inline
     * instead of deferring it, so a frame is fully applied before the next is
     * offered and the world never sees two ticks' worth of deltas at once.
     */
    fun tick() {
        if (closed || paused) {
            resync()
            return
        }
        val now = System.currentTimeMillis()
        val target = anchorPlayMs + ((now - anchorRealMs) * speed).toInt()
        feedUntil(target)
        // Clamped, so a recording that has run out leaves the playhead at its end
        // rather than climbing past it for as long as the viewer stands there.
        positionMillis = target.coerceAtMost(endMillis)
    }

    /** Feed every frame up to [target] millis. */
    private fun feedUntil(target: Int) {
        var fed = 0
        while (cursor < stream.frameCount && stream.timeAt(cursor) <= target) {
            feed(cursor)
            cursor++
            // A long stall in the stream — a chunk-heavy first second, a seek that
            // landed just before one — must not be paid for in one frame of the
            // game. The rest arrives next tick; the playhead already knows it is
            // behind and will catch up.
            if (++fed >= MAX_FRAMES_PER_TICK && !seeking) break
        }
    }

    /**
     * Whether the client has been told configuration is over.
     *
     * Tracked rather than assumed because a recording may not contain the packet
     * that says so — see [ensureHandoff].
     */
    private var handedOff = false

    /**
     * Make sure the configuration phase is closed before a play frame is fed.
     *
     * The client leaves configuration only when it decodes
     * `ClientboundFinishConfigurationPacket`; that is what builds the
     * `ClientPacketListener`, the level and the player. A stream that lacks it is
     * not slightly wrong, it is unplayable — every play frame afterwards is handed
     * to a configuration-phase decoder that has no such packet id, and the first
     * one faults the connection.
     *
     * Recordings made before the tap was fixed lack exactly that packet: the
     * capture dropped it because the server's pipeline removes its encoder while
     * encoding it. Synthesising it is safe and exact — the packet has no payload
     * beyond its id, and the phase boundary is already recorded per frame — so the
     * matches those recordings describe stay watchable instead of becoming a
     * migration.
     */
    private fun ensureHandoff(channel: EmbeddedChannel) {
        if (handedOff) return
        handedOff = true
        log.warn(
            "recording has no finish-configuration packet; synthesising the handoff " +
                "(recorded by a build that dropped it)"
        )
        val buf = Unpooled.buffer(2)
        writeVarInt(buf, 1)
        writeVarInt(buf, FINISH_CONFIGURATION_ID)
        runCatching { channel.writeInbound(buf) }
            .onFailure { log.warn("could not synthesise the configuration handoff", it) }
        runCatching { channel.releaseOutbound() }
    }

    private fun feed(frame: Int) {
        val channel = this.channel ?: return
        val length = stream.lengthAt(frame)
        // Grown first, before anything reads: `payloadInto` requires a buffer at
        // least as large as the frame, and a 64 KB chunk or tag packet is ordinary.
        if (buffer.size < length) buffer = ByteArray(Integer.highestOneBit(length) * 2)
        stream.payloadInto(frame, buffer)

        if (stream.protocolAt(frame) == ReplayProtocol.CONFIGURATION) {
            // The recording closes its own configuration phase; nothing to synthesise.
            if (length > 0 && leadingVarInt(length) == FINISH_CONFIGURATION_ID) handedOff = true
        } else if (stream.protocolAt(frame) == ReplayProtocol.PLAY) {
            ensureHandoff(channel)
        }
        // Length-prefixed, because the pipeline's head is the frame splitter: it is
        // the same buffer shape a socket would have delivered.
        val buf = Unpooled.buffer(length + 5)
        writeVarInt(buf, length)
        buf.writeBytes(buffer, 0, length)
        try {
            channel.writeInbound(buf)
            // Belt and braces: the sink swallows packets, but the pipeline still
            // moves the occasional non-packet outbound message, and an
            // EmbeddedChannel queues anything that reaches its head forever.
            channel.releaseOutbound()
            // Checked here, on the frame that caused it, rather than waiting for
            // the next write to throw `ClosedChannelException` and blaming the
            // frame that merely found the door shut.
            //
            // `Connection.exceptionCaught` logs a handler fault at **DEBUG** and
            // then disconnects, so on a default log config a failure here leaves
            // no stack trace at all — only the reason it stored on the way out.
            // That reason is the one durable record of what went wrong, which is
            // why it is read back rather than left to a log level nobody has on.
            if (!channel.isOpen) {
                log.warn("replay stopped: the client closed the connection applying {} — {}", describe(frame), reason())
                closeWithMessage("That replay could not be played — the recording faulted this client")
            }
        } catch (e: java.nio.channels.ClosedChannelException) {
            log.warn("replay stopped before {}: the connection was already closed — {}", describe(frame), reason())
            closeWithMessage("That replay could not be played — the recording faulted this client")
        } catch (e: Exception) {
            log.warn("replay {} could not be applied; stopping", describe(frame), e)
            closeWithMessage("That replay could not be played to the end")
        }
    }

    /** Why the client hung up, as it recorded it on the way out. */
    private fun reason(): String =
        connection?.disconnectionDetails?.reason()?.string
            ?: "no reason recorded; raise net.minecraft.network.Connection to DEBUG for the stack trace"

    /**
     * One frame, named the way a protocol problem needs it named: which phase it
     * was decoded in and which packet it is. Bytes alone are unactionable, and
     * "frame 38" only meant something after counting the configuration phase by
     * hand.
     */
    private fun describe(frame: Int): String {
        val phase = when (stream.protocolAt(frame)) {
            ReplayProtocol.CONFIGURATION -> "configuration"
            ReplayProtocol.PLAY -> "play"
            else -> "unknown-phase"
        }
        val length = stream.lengthAt(frame)
        // Read out of [buffer]: every caller is downstream of the `payloadInto`
        // in [feed], so this frame's bytes are already there and re-reading the
        // file to name it would be work for a log line.
        val idText = if (length > 0) "0x%02x".format(leadingVarInt(length)) else "?"
        return "frame $frame ($phase packet $idText, $length bytes, at ${stream.timeAt(frame)}ms)"
    }

    /** The packet id: the leading varint of whatever is currently in [buffer]. */
    private fun leadingVarInt(length: Int): Int {
        var result = 0
        var shift = 0
        for (index in 0 until minOf(5, length)) {
            val byte = buffer[index].toInt()
            result = result or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    /**
     * Move the playhead.
     *
     * Forwards is cheap: feed the frames in between without waiting for the clock.
     * Backwards is a restart — the stream is deltas, and there is no packet that
     * says "put that block back" — so the world is torn down and rebuilt up to the
     * target. That is slow and honest, and the caller shows it as loading.
     */
    fun seek(millis: Int) {
        if (closed) return
        val target = millis.coerceIn(0, endMillis)
        seeking = true
        try {
            if (target < positionMillis) {
                restart()
            }
            feedUntil(target)
            positionMillis = target
        } finally {
            seeking = false
            resync()
        }
    }

    /** Where a viewer should start: the match, not the handshake before it. */
    fun seekToGameStart() = seek(meta.gameStartMillis.toInt())

    private fun restart() {
        teardown()
        start()
    }

    /** Re-anchor the playhead's clock, so a pause or a seek does not fast-forward. */
    private fun resync() {
        anchorRealMs = System.currentTimeMillis()
        anchorPlayMs = positionMillis
    }

    /**
     * Force the viewer into spectator, whatever the recorded player was in.
     *
     * The recording sets the game mode the player actually had — survival, and its
     * hunger, its damage and its inability to fly through the base you are trying
     * to look at. A viewer is not playing; the recorded world cannot be affected by
     * them either way, so the only question is whether the camera is free.
     */
    fun forceSpectator() {
        runCatching { minecraft.gameMode?.setLocalMode(GameType.SPECTATOR) }
    }

    private fun teardown() {
        connection?.let { runCatching { it.disconnect(net.minecraft.network.chat.Component.literal("Replay ended")) } }
        channel?.let { runCatching { it.close() } }
        connection = null
        channel = null
        // Back to where the viewer came from, or the title screen: the client is
        // in a level that has no server behind it, and leaving it is the only way
        // to make it stop existing.
        minecraft.level?.let {
            runCatching {
                minecraft.disconnect(parent ?: net.minecraft.client.gui.screens.TitleScreen(), false)
            }
        }
    }

    /** Stop playing and put the player back where they came from. */
    fun close() {
        if (closed) return
        closed = true
        teardown()
        runCatching { stream.close() }
    }

    /**
     * Give up on the recording and say so.
     *
     * A notice rather than a disconnect screen: the wind-down already puts the
     * player back on the screen they opened the replay from, and stacking a second
     * "you have been disconnected" on top of that describes a server problem that
     * did not happen.
     */
    private fun closeWithMessage(message: String) {
        close()
        minecraft.execute { dev.yabranked.client.RankedToast.showError("Replay", message) }
    }

    /**
     * Swallows every outbound packet, tail-most in the pipeline so it sees a write
     * before any encoder does.
     *
     * Only [Packet]s are dropped. The protocol-configuration tasks the pipeline
     * moves as ordinary outbound messages are *not* packets, and they have to keep
     * travelling — the inbound protocol swap that turns configuration into play is
     * one of them.
     */
    private class OutboundSink : io.netty.channel.ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is Packet<*>) {
                io.netty.util.ReferenceCountUtil.release(msg)
                // Succeeded, because from the client's point of view it *was*
                // sent. A failed promise is a send error, and `Connection` logs
                // and faults on those.
                promise.setSuccess()
                return
            }
            ctx.write(msg, promise)
        }
    }

    companion object {
        /** Netty handler name for [OutboundSink]. */
        private const val SINK = "yabranked_replay_sink"

        /**
         * `ClientboundFinishConfigurationPacket`'s id: its position in
         * `ConfigurationProtocols.CLIENTBOUND`'s registration order, which is what
         * `IdDispatchCodec` numbers packets by.
         */
        private const val FINISH_CONFIGURATION_ID = 3

        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 8f

        /**
         * Frames applied per client tick outside a seek. A recording's first
         * second is dozens of full chunks, and applying all of it in one frame is
         * a visible stall; the playhead runs on the wall clock and simply catches
         * up over the next few ticks.
         */
        private const val MAX_FRAMES_PER_TICK = 400

        /**
         * Open a downloaded recording, or null when its primary stream cannot be
         * read. [dir] is a [ReplayCache] directory.
         */
        fun open(meta: MatchReplayMeta, dir: Path, primaryIndex: Int, parent: Screen?): ReplayPlayback? {
            val stream = ReplayStreamFile.open(dir.resolve("$primaryIndex.yabr")) ?: return null
            if (stream.isEmpty) {
                stream.close()
                return null
            }
            return ReplayPlayback(meta, primaryIndex, stream, parent)
        }

        private fun writeVarInt(buf: io.netty.buffer.ByteBuf, value: Int) {
            var remaining = value
            while (true) {
                if (remaining and 0x7F.inv() == 0) {
                    buf.writeByte(remaining)
                    return
                }
                buf.writeByte((remaining and 0x7F) or 0x80)
                remaining = remaining ushr 7
            }
        }
    }
}
