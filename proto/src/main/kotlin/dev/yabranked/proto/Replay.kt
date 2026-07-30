package dev.yabranked.proto

import kotlinx.serialization.Serializable

/**
 * Match replays: the match itself, rerun inside the game.
 *
 * A replay is **the recorded packet stream**, not a description of what
 * happened. The agent taps every clientbound packet the server sends to each
 * participant and stores the bytes verbatim; the client plays them back into a
 * detached [net.minecraft.network.Connection], so the viewer stands in the real
 * world with the real terrain, the real bases, the real chests and the real
 * players moving through it. The earlier format recorded one position sample a
 * second and drew it as a chart, which could show a route and could never show
 * a match.
 *
 * The consequence is that a recording is **large and binary**, so this file
 * describes only the *index*: what the recording is of, which streams it has,
 * and the timeline markers a viewer needs in order to seek. The packet bytes
 * live beside it in a blob store and are fetched separately, once, to a local
 * cache — see `ReplayApi` for the transfer and `ReplayBlobStore` for the store.
 *
 * Two audiences, one recording, unchanged: a player watches their own match
 * back, and a moderator watches a *reported* match to decide whether an
 * accusation holds up — which is now a question the recording can actually
 * answer, because it contains what the accused player's client was told, block
 * for block.
 */

/** One cell of the 5×5 card, and who got there first. */
@Serializable
data class ReplayCell(
    /** 0..24, row-major: `index / 5` is the row, `index % 5` the column. */
    val index: Int,
    /** YAB's objective id, e.g. `"minecraft:diamond"`. */
    val objectiveId: String,
    /** YAB difficulty tier, "S".."D"; empty when the card did not say. */
    val tier: String = "",
    /** Who claimed it, null while unclaimed at the end of the match. */
    val claimedBy: PlayerRef? = null,
    /** Which side claimed it; null when unclaimed. */
    val claimedByTeam: Int? = null,
    /** Seconds from the start of the match, to the polling resolution. */
    val claimedAtSeconds: Long? = null,
)

/**
 * The card as it ended up.
 *
 * Still polled rather than evented, because the bingo API exposes the board and
 * `hasPlayerAchieved` but no claim event. It survives the move to packet
 * capture because it is what the replay's card overlay and its timeline are
 * drawn from: the packet stream contains YAB's card *rendering*, which a viewer
 * cannot index into by objective.
 */
@Serializable
data class ReplayBoard(
    /** Always 5 today; carried so a future card size does not need a new type. */
    val size: Int = 5,
    val cells: List<ReplayCell> = emptyList(),
    val cardSeed: Long? = null,
)

@Serializable
enum class ReplayEventType {
    GAME_START,
    /** An objective was claimed; [ReplayEvent.cell] says which. */
    CLAIM,
    DEATH,
    JOIN,
    LEAVE,
    FORFEIT,
    GAME_END,
    /** Anything the recorder wanted to mark but has no type for. */
    NOTE,
}

/**
 * One marked moment. These are the ticks on the replay timeline, and what "jump
 * to when that item was claimed" seeks to.
 */
@Serializable
data class ReplayEvent(
    val atSeconds: Long,
    val type: ReplayEventType,
    val player: PlayerRef? = null,
    val team: Int? = null,
    /** Cell index for [ReplayEventType.CLAIM]. */
    val cell: Int? = null,
    /** Short human-readable line, already formatted by the recorder. */
    val detail: String = "",
)

/**
 * One recorded perspective: everything the server told one player's client.
 *
 * There is a stream per participant rather than one for the match, because a
 * packet stream only ever contains what its recipient was sent — the chunks
 * *they* had loaded, the entities in *their* tracking range. One stream is one
 * player's field of view, and the viewer switches between them.
 *
 * The streams merge cleanly on playback because the ids in them are the
 * server's: entity id 42 and chunk (13, -7) mean the same thing in every
 * stream, so feeding a second stream into the same world widens it rather than
 * corrupting it. Only the packets addressed to the recipient personally —
 * health, inventory, camera, position — are taken from one stream at a time.
 */
@Serializable
data class ReplayStreamInfo(
    /** Position in the recording; the blob is addressed by it. */
    val index: Int,
    val player: PlayerRef,
    val team: Int = 0,
    val sizeBytes: Long = 0,
    val packetCount: Long = 0,
    /** Millis from the start of the recording to this stream's first packet. */
    val startMillis: Long = 0,
    /** Millis to its last, so a viewer can show when a player was connected. */
    val endMillis: Long = 0,
    /**
     * True when the recorder stopped writing this stream because it hit its own
     * size cap. The stream is still valid up to [endMillis] — it just is not the
     * whole match, and a viewer that does not say so is lying about a recording
     * a moderator may be deciding a ban on.
     */
    val truncated: Boolean = false,
)

/** Where a player was, once. Times are millis from the recording's start. */
@Serializable
data class ReplayPose(
    val atMillis: Int,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    /** Dimension id, so a body is not drawn in a world its player had left. */
    val dimension: String = "",
)

/**
 * One player's movement through the match, sampled.
 *
 * This exists because **a packet capture cannot contain the recorded player's own
 * body**. Movement is client-authoritative: a server never sends a player their
 * own position, so player N is the one player absent from stream N. Watching your
 * own match back would show the world you moved through and never you moving
 * through it, and following a player from another player's stream would lose them
 * the moment they left tracking range.
 *
 * So the packets carry the world and this carries the people. It is sampled rather
 * than exact for the same reason the v1 format was — the poses are for a body to
 * be drawn at and a camera to sit on, not for arbitrating a half-second race — and
 * at [SAMPLE_HZ] a twenty-minute match is a few hundred kilobytes against a
 * recording of tens of megabytes.
 */
@Serializable
data class ReplayTrack(
    val player: PlayerRef,
    val team: Int = 0,
    val poses: List<ReplayPose> = emptyList(),
) {
    companion object {
        /** Samples a second. Enough that a walking player does not stutter. */
        const val SAMPLE_HZ = 5
    }
}

/**
 * The index of a recording: what it is, what it contains, and where to seek.
 *
 * [version] is the recorder's format version, not the mod version. A viewer
 * that does not understand a newer one says so rather than half-decoding a
 * match and drawing it as if it were the whole truth — and since v1 recordings
 * were position samples with no packets in them at all, a v1 file is not a
 * partially-readable v2 file, it is a different thing wearing the same name.
 */
@Serializable
data class MatchReplayMeta(
    val matchId: String,
    val format: MatchFormat = MatchFormat.LOCKOUT_1V1,
    /** Epoch seconds the match started, so timestamps have an anchor. */
    val startedAt: Long = 0,
    val durationSeconds: Long = 0,
    /**
     * Epoch millis of the recording's own zero — the moment the first player's
     * connection was tapped, which is *earlier* than [startedAt].
     *
     * The two differ because the capture has to begin at the configuration
     * handshake: the registry data a client needs in order to make sense of a
     * single play-phase packet is sent once, before anybody has joined a world,
     * let alone started a match. Every frame timestamp in the streams is millis
     * from here.
     */
    val recordedFrom: Long = 0,
    /**
     * Millis from [recordedFrom] to the start of the match, i.e. where the
     * playhead should open. Everything before it is the lobby and the handshake.
     */
    val gameStartMillis: Long = 0,
    val board: ReplayBoard = ReplayBoard(),
    val events: List<ReplayEvent> = emptyList(),
    val streams: List<ReplayStreamInfo> = emptyList(),
    /** Where each player was, so the viewer can draw them; see [ReplayTrack]. */
    val tracks: List<ReplayTrack> = emptyList(),
    /**
     * The Minecraft version the recording was made against, e.g. `"26.2"`.
     *
     * A packet stream is only decodable by the protocol that wrote it. A client
     * on a different version must refuse the file outright, because the failure
     * mode of trying anyway is a malformed packet somewhere in the middle of a
     * world that has already been drawn.
     */
    val gameVersion: String = "",
    /**
     * The network protocol the frames are encoded in, which is the number that
     * actually decides decodability — two versions can share a protocol, and a
     * snapshot can change it without changing the name anybody sees.
     */
    val protocolVersion: Int = 0,
    val version: Int = CURRENT_VERSION,
) {
    /** Bytes across every stream — what the quota is actually spent on. */
    val totalBytes: Long get() = streams.sumOf { it.sizeBytes }

    /**
     * Whether a client on [protocol] can play it back at all. [gameVersion] is
     * carried for the message shown when it cannot; the protocol is the check.
     */
    fun playableOn(protocol: Int): Boolean =
        version == CURRENT_VERSION && (protocolVersion == 0 || protocolVersion == protocol)

    companion object {
        /** 1 was the position-sample format; 2 is the packet capture. */
        const val CURRENT_VERSION = 2
    }
}

/**
 * A replay as it appears in a list, without its packet streams.
 *
 * The recording is tens of megabytes; the "my replays" screen only needs to say
 * which match it was, how big it is and how long it will be kept.
 */
@Serializable
data class ReplaySummary(
    val matchId: String,
    val format: MatchFormat = MatchFormat.LOCKOUT_1V1,
    /** "win" | "loss" | "draw" | "void", from the caller's perspective. */
    val result: String = "void",
    val opponentName: String = "",
    val recordedAt: Long = 0,
    val durationSeconds: Long = 0,
    /** Kept indefinitely because the player asked for it. */
    val saved: Boolean = false,
    /** Epoch seconds this is deleted, or null when [saved] or under review. */
    val expiresAt: Long? = null,
    /** Kept because the match was reported, whatever the player chose. */
    val underReview: Boolean = false,
    /** How much space it takes; a number the quota screen now spends directly. */
    val sizeBytes: Long = 0,
    /**
     * False once the recording is complete. A checkpointed upload from a
     * container that is still running is playable, but only up to where it got
     * to, and telling a player that is better than letting the replay stop dead.
     */
    val partial: Boolean = false,
)

/**
 * How much replay storage this player may keep.
 *
 * Counted in **bytes as well as files**, because a packet capture is three
 * orders of magnitude larger than the sample track this replaced: ten saved
 * replays used to be a few megabytes and are now a few hundred, and a cap that
 * only counts files stops describing the thing being rationed.
 */
@Serializable
data class ReplayQuota(
    val used: Int = 0,
    val limit: Int = 0,
    val usedBytes: Long = 0,
    val limitBytes: Long = 0,
    /** Days an unsaved replay is kept before it is deleted. */
    val retentionDays: Int = 0,
) {
    val full: Boolean get() = used >= limit || (limitBytes > 0 && usedBytes >= limitBytes)

    /** Whether saving something of [bytes] would fit. */
    fun fits(bytes: Long): Boolean =
        used < limit && (limitBytes <= 0 || usedBytes + bytes <= limitBytes)
}

/** The "my replays" payload: the list plus the quota it is counted against. */
@Serializable
data class ReplayListResponse(
    val replays: List<ReplaySummary> = emptyList(),
    val quota: ReplayQuota = ReplayQuota(),
)
