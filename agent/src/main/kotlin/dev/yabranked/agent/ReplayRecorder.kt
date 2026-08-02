package dev.yabranked.agent

import dev.yabranked.proto.MatchReplayMeta
import dev.yabranked.proto.PlayerRef
import dev.yabranked.proto.ReplayBoard
import dev.yabranked.proto.ReplayCell
import dev.yabranked.proto.ReplayEvent
import dev.yabranked.proto.ReplayEventType
import dev.yabranked.proto.ReplayPose
import dev.yabranked.proto.ReplayStreamInfo
import dev.yabranked.proto.ReplayTrack
import me.jfenn.bingo.api.BingoApi
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import org.slf4j.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Records a match so it can be walked back through inside the game.
 *
 * The recording is two things at once, and only one of them is large:
 *
 * - **The packet streams** ([ReplayPacketTap]), which are the replay: every byte
 *   the server sent each player's client, from the configuration handshake on.
 *   Fed back into a client they reproduce the world, not a description of it.
 * - **The index** — the card and a timeline of marked moments — which is what a
 *   viewer seeks *with*. It is still polled rather than evented, because the bingo
 *   API exposes the board and `hasPlayerAchieved` and no claim event, so a claim is
 *   dated to when the poll first saw it: accurate to [INTERVAL_SECONDS], enough to
 *   order a board and to catch a run of claims no honest player could have made,
 *   not enough to arbitrate a half-second race.
 *
 * The card cannot come out of the packet stream even though the stream contains
 * YAB's rendering of it: a viewer needs "which cell holds which objective, claimed
 * by whom, when", and what the packets carry is item frames and text.
 *
 * Everything that reads game state does so on the server thread and hands the
 * result back; nothing here touches the world off-thread.
 */
class ReplayRecorder(
    private val config: AgentConfig,
    private val scheduler: ScheduledExecutorService,
    private val log: Logger,
    /** Owns the packet capture. Attached per connection, long before [start]. */
    private val tap: ReplayPacketTap = ReplayPacketTap(config, log),
    /** Ships stream bytes and the index to the backend. */
    private val uploader: ReplayUploader,
    /**
     * Seconds between partial uploads, 0 to record without them. A container can
     * die without ever reaching its own report — the backend settles a forfeit
     * over the player's token, an OOM kill, the liveness sweep voiding a match —
     * and the recording died with it every time, which is most of what "no replay
     * was recorded for that match" ever meant. Checkpointing means the worst case
     * is losing the last few seconds rather than the whole match.
     */
    private val checkpointSeconds: Long = 60,
) {
    private val events = java.util.Collections.synchronizedList(mutableListOf<ReplayEvent>())

    /** Cell index -> the claim already recorded for it, so a claim is dated once. */
    private val claimed = ConcurrentHashMap<Int, ReplayCell>()

    /** Board as read at the start; claims are merged onto it when reporting. */
    @Volatile private var board: ReplayBoard = ReplayBoard()

    /** Wall-clock ms the game started; 0 while it has not. */
    @Volatile private var startedAtMs = 0L

    /** Last health seen per player, to spot the transition into death. */
    private val lastHealth = ConcurrentHashMap<UUID, Int>()

    /**
     * Where each player has been, on the *tap's* clock.
     *
     * Timestamped against `recordingStartMs` rather than the game start because
     * these are drawn alongside packet frames, and two clocks that disagree by the
     * length of the lobby put a player's body where they were a minute ago.
     */
    private val poses = ConcurrentHashMap<UUID, MutableList<ReplayPose>>()

    @Volatile private var gameVersion = ""
    @Volatile private var protocolVersion = 0

    private var poller: ScheduledFuture<*>? = null
    private var poseSampler: ScheduledFuture<*>? = null
    private var checkpointer: ScheduledFuture<*>? = null

    /** So a broken capture complains once rather than every checkpoint. */
    private val warnedAboutNothingToUpload = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Uploads run here and nowhere else, so a checkpoint already in flight can
     * never race the finished recording's index. It is also why uploads never sit
     * on [scheduler]: a blocking POST there would stall the card polling that
     * shares that thread.
     */
    private val uploads = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "yabranked-replay").apply { isDaemon = true }
    }

    val isRecording: Boolean get() = startedAtMs != 0L && poller != null

    /** Seconds since the game started, which is the clock every event uses. */
    private fun now(): Long =
        if (startedAtMs == 0L) 0 else (System.currentTimeMillis() - startedAtMs) / 1000

    private fun refOf(player: AgentConfig.ExpectedPlayer) =
        PlayerRef(player.uuid.toString(), player.name)

    /**
     * Begin capturing a player's packets.
     *
     * Wired to `BEFORE_CONFIGURE` rather than to a join or to [start], because the
     * registry data a stream needs in order to be decodable at all is sent during
     * configuration — before there is a world to be in, never mind a match. The
     * capture therefore runs from the moment a player connects, and [start] only
     * decides when the *upload* begins: a container whose match never starts has
     * recorded a lobby, and nobody wants it.
     */
    fun attach(listener: ServerConfigurationPacketListenerImpl) {
        runCatching { tap.attach(listener) }
            .onFailure { log.warn("[yabranked] replay tap attach failed", it) }
    }

    fun start(server: MinecraftServer) {
        if (poller != null) return
        startedAtMs = System.currentTimeMillis()
        gameVersion = runCatching { server.serverVersion }.getOrDefault("")
        protocolVersion = runCatching { net.minecraft.SharedConstants.getProtocolVersion() }.getOrDefault(0)
        readBoard(server)
        mark(ReplayEventType.GAME_START, detail = "Match started")
        poller = scheduler.scheduleAtFixedRate(
            { runCatching { poll(server) }.onFailure { log.warn("[yabranked] replay poll failed", it) } },
            INTERVAL_SECONDS,
            INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        // Bodies are sampled on their own, much faster schedule; see [samplePoses].
        poseSampler = scheduler.scheduleAtFixedRate(
            {
                runCatching { server.execute { samplePoses(server) } }
                    .onFailure { log.warn("[yabranked] replay pose sample failed", it) }
            },
            POSE_INTERVAL_MS,
            POSE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
        if (checkpointSeconds > 0) {
            // Fixed *delay*, not rate: a slow upload must not queue a backlog of
            // them behind itself.
            checkpointer = uploads.scheduleWithFixedDelay(
                { runCatching { checkpoint() }.onFailure { log.warn("[yabranked] replay checkpoint failed", it) } },
                checkpointSeconds,
                checkpointSeconds,
                TimeUnit.SECONDS,
            )
        }
        log.info("[yabranked] recording replay for match ${config.matchId}")
    }

    fun stop() {
        poller?.cancel(false)
        poller = null
        poseSampler?.cancel(false)
        poseSampler = null
        // Cancelled, not interrupted: a checkpoint mid-POST finishes, and
        // [flush] queues behind it.
        checkpointer?.cancel(false)
        checkpointer = null
    }

    /** Upload the match so far, so a container that dies still leaves one. */
    private fun checkpoint() {
        val meta = build(now())
        if (meta == null) {
            // Said out loud, once. A checkpoint that quietly does nothing is how
            // a broken capture reached a live match and produced a log with no
            // replay in it and no complaint either — the tap had refused every
            // connection and nothing downstream had any way to notice.
            if (warnedAboutNothingToUpload.compareAndSet(false, true)) {
                log.warn(
                    "[yabranked] nothing to upload for match ${config.matchId}: " +
                        "recording=${startedAtMs != 0L} capturing=${tap.isCapturing}"
                )
            }
            return
        }
        uploader.checkpoint(tap.streamsInOrder(), meta)
    }

    /**
     * Upload the finished recording, behind any checkpoint still in flight, and
     * stop uploading afterwards. Blocks, so call it off the server thread.
     */
    fun flush(meta: MatchReplayMeta): Boolean = try {
        // The tap is closed first so the last frames are on disk before the
        // uploader reads the tail it is about to call final.
        tap.close()
        uploads.submit(java.util.concurrent.Callable { uploader.finish(tap.streamsInOrder(), meta) }).get()
    } catch (e: Exception) {
        log.warn("[yabranked] replay upload failed", e)
        false
    } finally {
        uploads.shutdown()
    }

    /** Note something the poller cannot see, e.g. a concession or a disconnect. */
    fun mark(
        type: ReplayEventType,
        player: AgentConfig.ExpectedPlayer? = null,
        cell: Int? = null,
        detail: String = "",
    ) {
        events += ReplayEvent(
            atSeconds = now(),
            type = type,
            player = player?.let(::refOf),
            team = player?.let { config.sideOf(it.uuid) },
            cell = cell,
            detail = detail,
        )
    }

    /**
     * The recording's index, or null when nothing was recorded — a match that
     * never started has a card and no story, and uploading an empty file for it
     * only costs the retention sweep work.
     */
    fun build(durationSeconds: Long): MatchReplayMeta? {
        if (startedAtMs == 0L || !tap.isCapturing) return null
        // Claims are merged in at the end rather than mutated in place: the
        // board read at the start is the objective list, and `claimed` is what
        // the poll discovered about it.
        val cells = board.cells.map { cell -> claimed[cell.index] ?: cell }
        return MatchReplayMeta(
            matchId = config.matchId,
            startedAt = startedAtMs / 1000,
            durationSeconds = durationSeconds,
            recordedFrom = tap.recordingStartMs,
            gameStartMillis = (startedAtMs - tap.recordingStartMs).coerceAtLeast(0),
            board = board.copy(cells = cells),
            events = synchronized(events) { events.toList() }.sortedBy { it.atSeconds },
            streams = tap.streamsInOrder().map { stream ->
                ReplayStreamInfo(
                    index = stream.index,
                    player = refOf(stream.player),
                    team = stream.team,
                    sizeBytes = stream.sizeBytes,
                    packetCount = stream.packetCount,
                    startMillis = stream.firstMillis.coerceAtLeast(0),
                    endMillis = stream.lastMillis,
                    truncated = stream.truncated,
                )
            },
            tracks = config.teams.flatMapIndexed { side, roster ->
                roster.map { player ->
                    ReplayTrack(player = refOf(player), team = side, poses = posesOf(player.uuid))
                }
            }.filter { it.poses.isNotEmpty() },
            gameVersion = gameVersion,
            protocolVersion = protocolVersion,
        )
    }

    /**
     * The card the recording is about.
     *
     * Every rated format shares one card between the sides, so this is that
     * card. A party match played with `sharedSeed = false` gives each side its
     * own, and the recording then describes the first side's board only — the
     * cell indices would otherwise mean a different objective per viewer, which
     * is worse than an honestly partial board.
     */
    private fun card() = BingoApi.INSTANCE?.cards?.let { cards ->
        runCatching { cards.getTeamCard(config.teamNameOf(0)) }.getOrNull()
            ?: runCatching { cards.getActiveCard() }.getOrNull()
    }

    /** Snapshot the objective list. Claims are discovered later, by [poll]. */
    private fun readBoard(server: MinecraftServer) {
        server.execute {
            val card = card() ?: return@execute
            val cells = buildList {
                for (row in 0 until SIZE) {
                    for (col in 0 until SIZE) {
                        val objective = runCatching { card.objective(col, row) }.getOrNull() ?: continue
                        add(
                            ReplayCell(
                                index = row * SIZE + col,
                                objectiveId = objective.objectiveId,
                                tier = objective.objectiveTier?.name.orEmpty(),
                            )
                        )
                    }
                }
            }
            board = ReplayBoard(size = SIZE, cells = cells, cardSeed = runCatching { card.seed }.getOrNull())
        }
    }

    /**
     * One round of polling. Runs on the server thread because it reads player
     * health and the card, both of which belong to it.
     */
    private fun poll(server: MinecraftServer) {
        val at = now()
        server.execute {
            sampleDeaths(server)
            sampleCard(at)
        }
    }

    /**
     * One position sample per player, on the server thread.
     *
     * Scheduled far faster than the card poll because this is what a body is drawn
     * at: a claim only needs to be ordered, a player needs to walk. See
     * [ReplayPacketTap] for why this cannot be read out of the packets instead —
     * movement is client-authoritative, so a player's own position is the one thing
     * their stream never contains.
     */
    private fun samplePoses(server: MinecraftServer) {
        val start = tap.recordingStartMs
        if (start == 0L) return
        val at = (System.currentTimeMillis() - start).coerceAtLeast(0).toInt()
        for (online in server.playerList.players) {
            val expected = config.playerOf(online.uuid) ?: continue
            poses.getOrPut(expected.uuid) { java.util.Collections.synchronizedList(mutableListOf()) }
                .add(
                    ReplayPose(
                        atMillis = at,
                        x = online.x,
                        y = online.y,
                        z = online.z,
                        yaw = online.yRot,
                        pitch = online.xRot,
                        dimension = runCatching { online.level().dimension().identifier().toString() }
                            .getOrDefault(""),
                    )
                )
        }
    }

    /** One player's track, copied under the list's own monitor — a checkpoint reads
     *  these while the server thread is still appending to them. */
    private fun posesOf(player: UUID): List<ReplayPose> {
        val track = poses[player] ?: return emptyList()
        return synchronized(track) { track.toList() }
    }

    /**
     * Deaths, derived from the health track rather than from an entity event: the
     * agent has no other reason to depend on the entity-events module, and a death
     * is a timeline marker here, not a fact the replay needs — the packet stream
     * already contains the death itself.
     */
    private fun sampleDeaths(server: MinecraftServer) {
        for (online in server.playerList.players) {
            val expected = config.playerOf(online.uuid) ?: continue
            val health = online.health.toInt()
            val previous = lastHealth.put(expected.uuid, health)
            if (previous != null && previous > 0 && health <= 0) {
                mark(ReplayEventType.DEATH, expected, detail = "${expected.name} died")
            }
        }
    }

    /**
     * Diff the card against what has already been claimed.
     *
     * Attribution is per player, not per team: `hasTeamAchieved` would only say
     * *a* teammate got there, and "which of the four of them" is exactly the
     * question a moderator is asking.
     */
    private fun sampleCard(at: Long) {
        val card = card() ?: return
        for (cell in board.cells) {
            if (claimed.containsKey(cell.index)) continue
            val objective = runCatching { card.objective(cell.index % SIZE, cell.index / SIZE) }.getOrNull()
                ?: continue
            val owner = config.roster.firstOrNull {
                runCatching { objective.hasPlayerAchieved(it.uuid) }.getOrDefault(false)
            } ?: continue
            val record = cell.copy(
                claimedBy = refOf(owner),
                claimedByTeam = config.sideOf(owner.uuid),
                claimedAtSeconds = at,
            )
            claimed[cell.index] = record
            mark(
                ReplayEventType.CLAIM,
                owner,
                cell = cell.index,
                detail = "${owner.name} claimed ${cell.objectiveId.substringAfter(':')}",
            )
        }
    }

    private companion object {
        /** Card side. YAB is 5×5 and the wire format carries the number anyway. */
        const val SIZE = 5

        /**
         * Seconds between card polls. One a second is the finest resolution the
         * bingo API can honestly be read at without the poll itself costing more
         * than the timeline it produces.
         */
        const val INTERVAL_SECONDS = 1L

        /**
         * Milliseconds between position samples — 5 Hz, matching
         * `ReplayTrack.SAMPLE_HZ`. A body drawn from these has to keep up with a
         * running player, which is a different job from ordering a claim, so it is
         * sampled five times as often and costs a few hundred kilobytes against a
         * recording of tens of megabytes.
         */
        const val POSE_INTERVAL_MS = 200L
    }
}
