package dev.yabranked.agent

import dev.yabranked.agent.mixin.ConnectionAccessor
import dev.yabranked.agent.mixin.PacketEncoderAccessor
import dev.yabranked.agent.mixin.ServerCommonPacketListenerAccessor
import dev.yabranked.agent.mixin.ServerConfigurationPacketListenerAccessor
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.HandlerNames
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import org.slf4j.Logger
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Records what each player's client was *told*, byte for byte.
 *
 * This is the replay. Everything else in the recording — the card, the timeline —
 * is an index into it. A packet stream is the only description of a match that can
 * be put back into a client and produce the match again: the terrain as it was
 * generated, the chests as they were looted, the other player walking past. The
 * format this replaced sampled positions once a second and drew them as a chart,
 * which could show that someone crossed a river and could never show what they
 * did at the other side.
 *
 * ## Where the bytes are taken from
 *
 * The tap is a Netty handler inserted **immediately head-ward of the encoder**,
 * which on a server pipeline puts it between `encoder` and `compress`. Outbound
 * data travels tail to head, so by the time it reaches the tap a packet has been
 * bundle-split and encoded to bytes, and has not yet been compressed or
 * encrypted. That is the one position in the pipeline where the bytes are both
 * self-contained and independent of the server's settings: a recording made with
 * `network-compression-threshold=256` is identical to one made with it off, and
 * `online-mode` does not change a single byte of the file.
 *
 * Login and handshake frames are dropped. They describe how *this* client
 * authenticated — an encryption request, a compression threshold, a profile
 * hand-off — and none of it means anything to a viewer, which starts its fake
 * connection in the configuration phase with a profile it makes up. Configuration
 * frames are kept and are not optional: they carry the dynamic registries, and a
 * client that has not been told the registries cannot decode a single play-phase
 * packet.
 *
 * ## Where the bytes go
 *
 * To a file per player under [dir], immediately. Holding a match in memory is not
 * an option at this size — a 20-minute stream is tens of megabytes and there is
 * one per participant — and the container that would be holding it is the same
 * one the match is being played on. [ReplayUploader] ships the file's tail to the
 * backend as it grows, so a container that dies mid-match still leaves a replay
 * that plays up to the moment it died.
 */
class ReplayPacketTap(
    private val config: AgentConfig,
    private val log: Logger,
    /** Where stream files are written; created on first use. */
    private val dir: Path = Path.of(System.getProperty("java.io.tmpdir"), "yabranked-replay"),
    /** Per-stream cap. Past it the stream is closed and marked truncated. */
    private val maxBytesPerStream: Long = DEFAULT_MAX_BYTES,
) {
    /** Streams by player, one per roster entry, created on that player's first connection. */
    private val streams = ConcurrentHashMap<UUID, ReplayStream>()

    /**
     * Epoch millis of the recording's zero. Set by the first attach and never
     * moved: every stream shares it, which is what lets a viewer feed two of them
     * into one world and have the two players be in the same place at the same
     * time.
     */
    @Volatile
    var recordingStartMs: Long = 0L
        private set

    /** Whether anything has been captured at all. */
    val isCapturing: Boolean get() = recordingStartMs != 0L

    fun streamsInOrder(): List<ReplayStream> =
        config.roster.mapNotNull { streams[it.uuid] }.sortedBy { it.index }

    /**
     * Start capturing everything sent to [listener]'s client.
     *
     * Called from Fabric's `BEFORE_CONFIGURE`, which is the last moment before
     * the registry sync starts and therefore the earliest point at which the
     * capture can be complete. Attaching later by even one packet produces a file
     * that decodes for a while and then does not.
     */
    fun attach(listener: ServerConfigurationPacketListenerImpl) {
        val connection = (listener as ServerCommonPacketListenerAccessor).yabrankedConnection()
        // The listener's own profile, not `Connection.getIntendedProfileId()`:
        // that is only set by the memory and transfer path
        // (`ServerConnectionListener.acceptChannel`) and is null for every
        // connection a TCP match server ever accepts. Reading it and returning
        // silently is what made the first live run record nothing at all.
        val uuid = (listener as ServerConfigurationPacketListenerAccessor).yabrankedGameProfile()?.id
            ?: connection.intendedProfileId
        if (uuid == null) {
            log.warn("[yabranked] a connection reached configuration with no profile; not recording it")
            return
        }
        val player = config.playerOf(uuid)
        if (player == null) {
            // Not an error: join gating rejects strangers, and this is the
            // handshake of one being rejected. Logged because "nothing was
            // recorded" must never be a silent outcome again.
            log.info("[yabranked] not recording $uuid — not in this match's roster")
            return
        }
        val channel = (connection as ConnectionAccessor).yabrankedChannel()
        if (channel == null) {
            log.warn("[yabranked] no channel behind ${player.name}'s connection; not recording it")
            return
        }
        val pipeline = channel.pipeline()
        if (pipeline.get(HANDLER) != null) return

        // The encoder is named `outbound_config` until the first protocol is set
        // up and `encoder` from then on. Login is over by the time we are called,
        // so it is the latter — but naming only one of them would make this fail
        // silently rather than loudly if that ever stopped being true.
        val anchor = when {
            pipeline.get(HandlerNames.ENCODER) != null -> HandlerNames.ENCODER
            pipeline.get(HandlerNames.OUTBOUND_CONFIG) != null -> HandlerNames.OUTBOUND_CONFIG
            else -> {
                log.warn("[yabranked] replay tap found no encoder in the pipeline; not recording ${player.name}")
                return
            }
        }

        // Under the map's own monitor rather than `computeIfAbsent`: opening a
        // stream can fail, and a failed open must not be cached as a null.
        val stream = synchronized(streams) {
            streams[uuid] ?: open(player)?.also { streams[uuid] = it }
        } ?: return
        try {
            pipeline.addBefore(anchor, HANDLER, TapHandler(stream) as ChannelHandler)
        } catch (e: Exception) {
            log.warn("[yabranked] replay tap could not attach for ${player.name}", e)
            return
        }
        log.info("[yabranked] recording packets for ${player.name} (stream ${stream.index})")
    }

    private fun open(player: AgentConfig.ExpectedPlayer): ReplayStream? = try {
        if (recordingStartMs == 0L) recordingStartMs = System.currentTimeMillis()
        val matchDir = dir.resolve(config.matchId)
        Files.createDirectories(matchDir)
        val index = config.roster.indexOfFirst { it.uuid == player.uuid }.coerceAtLeast(0)
        ReplayStream(
            index = index,
            player = player,
            team = config.sideOf(player.uuid) ?: 0,
            file = matchDir.resolve("stream-$index.yabr"),
            recordingStartMs = recordingStartMs,
            maxBytes = maxBytesPerStream,
            log = log,
        )
    } catch (e: Exception) {
        log.warn("[yabranked] could not open a replay stream for ${player.name}", e)
        null
    }

    /** Close every stream. The files stay, so a pending upload can still read them. */
    fun close() {
        streams.values.forEach(ReplayStream::close)
    }

    /**
     * The handler itself. One per connection, and only ever invoked on that
     * connection's event loop — Netty serializes writes onto it — which is why
     * the protocol cache below needs no synchronization.
     */
    private inner class TapHandler(private val stream: ReplayStream) : ChannelOutboundHandlerAdapter() {
        private var lastEncoder: Any? = null
        private var lastProtocol: Byte = PROTOCOL_SKIP

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is ByteBuf && msg.isReadable) {
                val protocol = protocolOf(ctx)
                if (protocol != PROTOCOL_SKIP) {
                    // Read out of the buffer without moving its indices: this is
                    // a live packet on its way to a player, and consuming it here
                    // would mean recording the match instead of playing it.
                    val length = msg.readableBytes()
                    val bytes = ByteArray(length)
                    msg.getBytes(msg.readerIndex(), bytes)
                    stream.append(protocol, bytes)
                }
            }
            ctx.write(msg, promise)
        }

        /**
         * The protocol whatever currently sits under `encoder` encodes for.
         *
         * Cached by identity rather than looked up and unwrapped every packet:
         * the pipeline replaces the encoder instance on a protocol change and on
         * nothing else, so a changed instance is exactly the signal wanted.
         */
        private fun protocolOf(ctx: ChannelHandlerContext): Byte {
            // No encoder means the protocol is *changing right now*, and the frame
            // in flight is the one that changes it.
            //
            // `ProtocolSwapHandler.handleOutboundTerminalPacket` removes the
            // `encoder` handler as it finishes encoding a terminal packet — the
            // configuration-ending one — and this tap sits head-ward of the
            // encoder, so it looks the handler up *after* that removal and finds
            // nothing. Skipping the frame there dropped
            // `ClientboundFinishConfigurationPacket` out of every recording, and a
            // stream missing it is one where the viewer never leaves the
            // configuration phase and faults on the first play packet instead. The
            // last protocol is the right label: that packet was encoded by the
            // encoder that has only just been taken away.
            val encoder = ctx.pipeline().get(HandlerNames.ENCODER) ?: return lastProtocol
            if (encoder !== lastEncoder) {
                lastEncoder = encoder
                lastProtocol = runCatching {
                    codeOf((encoder as PacketEncoderAccessor).yabrankedProtocolInfo().id())
                }.getOrDefault(PROTOCOL_SKIP)
            }
            return lastProtocol
        }
    }

    companion object {
        /** Netty handler name. Namespaced so it cannot collide with a mod's own. */
        const val HANDLER = "yabranked_replay_tap"

        // The wire constants live in ReplayFormat (:agent-core) so the writer
        // can be tested without Minecraft on the classpath. Re-exported here
        // because this is where the encoding decisions are made.
        val MAGIC = ReplayFormat.MAGIC
        const val FORMAT_VERSION: Byte = ReplayFormat.FORMAT_VERSION

        /** Not recorded: the frame belongs to a phase a viewer never replays. */
        const val PROTOCOL_SKIP: Byte = ReplayFormat.PROTOCOL_SKIP
        const val PROTOCOL_CONFIGURATION: Byte = ReplayFormat.PROTOCOL_CONFIGURATION
        const val PROTOCOL_PLAY: Byte = ReplayFormat.PROTOCOL_PLAY

        /**
         * 96 MB a player. A busy 20-minute match is 20-50 MB, so this is headroom
         * rather than a budget — but it is a hard stop, because the alternative is
         * a runaway stream filling the container's disk out from under the match
         * it is recording.
         */
        const val DEFAULT_MAX_BYTES = 96L * 1024 * 1024

        fun codeOf(protocol: ConnectionProtocol): Byte = when (protocol) {
            ConnectionProtocol.CONFIGURATION -> PROTOCOL_CONFIGURATION
            ConnectionProtocol.PLAY -> PROTOCOL_PLAY
            else -> PROTOCOL_SKIP
        }
    }
}
