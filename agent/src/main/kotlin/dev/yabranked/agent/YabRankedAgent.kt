package dev.yabranked.agent

import me.jfenn.bingo.api.BingoApi
import me.jfenn.bingo.api.BingoEvents
import me.jfenn.bingo.api.event.GameEndedEvent
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.networking.v1.ServerConfigurationConnectionEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class YabRankedAgent : DedicatedServerModInitializer {

    private val log = LoggerFactory.getLogger("yabranked-agent")
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "yabranked-agent").apply { isDaemon = true }
    }

    private enum class Phase { CONFIGURING, WAITING_FOR_PLAYERS, PLAYING, REPORTED }

    private companion object {
        /** ~60s of 3s retries for YAB to reach PREGAME and accept config. */
        const val MAX_CONFIG_ATTEMPTS = 20
        /** ~30s of 1s retries for a joining player to become resolvable. */
        const val MAX_ASSIGN_ATTEMPTS = 30
        const val MAX_START_ATTEMPTS = 10

        /**
         * Ceiling on the gap between a settled match and the disconnect, whatever
         * `YABRANKED_POSTGAME_SECONDS` says.
         *
         * It is a ceiling rather than a setting because the window is a hazard,
         * not a feature: for as long as players are on the match server, YAB's
         * postgame "return to lobby" is one click away, and clicking it resets the
         * worlds into a fresh lobby game. See [endSessionAndShutdown].
         */
        const val MAX_WIND_DOWN_SECONDS = 3L

        /** How often the arrival/start deadlines are re-checked. */
        const val CHECK_INTERVAL_SECONDS = 5L

        /**
         * Added to the no-show timeout for the "everyone is here but the game
         * never started" deadline. Three minutes past the point where a match
         * would otherwise be voided for a missing player.
         */
        const val START_GRACE_SECONDS = 180L
    }

    private lateinit var config: AgentConfig
    private lateinit var reporter: BackendReporter
    private lateinit var replay: ReplayRecorder
    private var server: MinecraftServer? = null

    private val phase = AtomicReference(Phase.CONFIGURING)
    private var noShowTimer: ScheduledFuture<*>? = null

    /**
     * Expected players who have connected at least once.
     *
     * The no-show rule is about arrival, and this is the only record of it. It
     * used to be inferred from the phase — which moves to PLAYING when *YAB*
     * starts the game, a different question entirely. On a CPU-limited container
     * the gap between the two is over a minute: an observed match had both
     * players on their teams for 80 seconds and was voided six seconds before
     * the countdown ended, because a 60s spawn search plus a 10s chunk preload
     * outlasted a 90s timer that had already been satisfied.
     */
    private val arrived = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()

    /** When the backend was told this server is ready; both deadlines run from it. */
    private var readyAt: java.time.Instant = java.time.Instant.now()

    /**
     * How long everybody-is-here may fail to become a running game.
     *
     * Generous on purpose: what it has to cover is a slow container doing real
     * work — YAB's spawn search runs to a 60s timeout of its own and the chunk
     * preload after it is measured in tens of seconds. Anything short enough to
     * feel responsive here would void matches that were about to start.
     */
    private val startDeadlineSeconds: Long get() = config.noShowTimeoutSeconds + START_GRACE_SECONDS

    /** Expected players who are not connected right now. */
    private fun missingPlayers(): List<AgentConfig.ExpectedPlayer> =
        config.roster.filter { it.uuid !in arrived }

    /** Set when the agent decides the outcome itself (abandon/no-show). */
    private val forcedOutcome = AtomicReference<WireOutcome?>(null)

    /** UUID of the player who forfeited (concede or no-show); null for a clean finish. */
    private val forfeiter = AtomicReference<UUID?>(null)

    /**
     * Which side won, for matches with more than two of them. The outcome enum
     * can only name team A or team B, so a three-way free-for-all is
     * unattributable without this.
     */
    private val winningSide = AtomicReference<Int?>(null)

    private val assignedPlayers = java.util.concurrent.ConcurrentHashMap.newKeySet<UUID>()
    private val startRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onInitializeServer() {
        val parsed = AgentConfig.fromEnv()
        if (parsed == null) {
            log.info("[yabranked] no match environment configured; agent inactive")
            return
        }
        config = parsed
        reporter = BackendReporter(config, log)
        replay = ReplayRecorder(
            config, scheduler, log,
            uploader = ReplayUploader(
                log,
                append = reporter::appendReplayStream,
                putMeta = reporter::reportReplayMeta,
            ),
            checkpointSeconds = config.replayCheckpointSeconds,
        )

        ServerLifecycleEvents.SERVER_STARTED.register { server -> onServerStarted(server) }

        // The packet capture starts here, at the configuration handshake, and not
        // at the match: a stream that does not contain the registry sync is not a
        // shorter replay, it is an undecodable one.
        ServerConfigurationConnectionEvents.BEFORE_CONFIGURE.register { handler, _ ->
            replay.attach(handler)
        }

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            onPlayerJoin(server, handler.player.uuid, handler.player.gameProfile.name)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            onPlayerDisconnect(server, handler.player.uuid)
        }

        BingoEvents.GAME_STARTED.register { _ ->
            // A reported match is over for good. YAB resets to its lobby when
            // its postgame timer ends and will happily start a *second* game
            // there — which must not re-arm the recorder, restart the clock or
            // put the agent back into PLAYING, where a disconnect would report
            // a forfeit for a match that was settled minutes ago.
            if (phase.get() == Phase.REPORTED) {
                log.info("[yabranked] ignoring a game started after the match was reported")
                return@register
            }
            log.info("[yabranked] game started")
            phase.set(Phase.PLAYING)
            noShowTimer?.cancel(false)
            this.server?.let(replay::start)
        }

        BingoEvents.GAME_ENDED.register { event -> onGameEnded(event) }
    }

    /**
     * Executes a command through the Brigadier dispatcher so failures are
     * detectable — YAB's command tree silently rejects (e.g. "Incorrect
     * argument") when its game scope is not in the expected state yet.
     */
    private fun command(server: MinecraftServer, command: String): Boolean {
        return try {
            server.commands.dispatcher.execute(command, server.createCommandSourceStack())
            log.info("[yabranked] > /$command  OK")
            true
        } catch (e: Exception) {
            log.warn("[yabranked] > /$command  FAILED: ${e.message}")
            false
        }
    }

    /**
     * Translates the backend's rule spec into YAB commands. Every command is
     * verified, so a format the server cannot honour fails loudly during
     * configuration instead of quietly producing the wrong game.
     */
    private fun applyRules(server: MinecraftServer): Boolean {
        val rules = config.rules
        val commands = buildList {
            add("bingo mode lockout ${rules.lockout}")
            add("bingo mode inventory ${rules.inventory}")
            add("bingo mode hidden_items ${rules.hiddenItems}")
            add("bingo mode consume_items ${rules.consumeItems}")
            add("bingo goal ${rules.goalCount} ${rules.goalType}")
            // lockout can in principle deadlock every line; let YAB end the
            // game rather than letting a match hang to the time limit
            add("bingo options stalemate end_game")
            add("bingo options end_when first_win")
            add("bingo options pvp ${rules.pvp}")
            add("bingo timelimit ${rules.timeLimitMinutes}")
            rules.difficulty?.let { add("bingo difficulty ${it.joinToString(" ")}") }
            add("bingo card seed ${config.cardSeed}")
            // the locator bar points straight at the opponent — in a race for
            // the same items that hands away their whole strategy
            add("gamerule locator_bar false")
        }
        return commands.all { command(server, it) }
    }

    private fun onServerStarted(server: MinecraftServer) {
        this.server = server
        registerForfeitCommand(server)
        scheduler.execute { configureWithRetry(server, attempt = 1) }
    }

    /**
     * YAB initializes its game scope after SERVER_STARTED, so the config
     * commands are retried until the API reports PREGAME and every command
     * verifies. Only then does the backend learn the server is ready.
     */
    private fun configureWithRetry(server: MinecraftServer, attempt: Int) {
        if (attempt > MAX_CONFIG_ATTEMPTS) {
            log.error("[yabranked] could not configure YAB after $MAX_CONFIG_ATTEMPTS attempts; voiding match")
            forcedOutcome.set(WireOutcome.VOID)
            reportAndShutdown(server, WireOutcome.VOID, durationSeconds = 0)
            return
        }

        val retry = Runnable {
            scheduler.schedule({ configureWithRetry(server, attempt + 1) }, 3, TimeUnit.SECONDS)
        }

        val api = BingoApi.INSTANCE
        if (api == null || api.game.status != me.jfenn.bingo.api.data.BingoGameStatus.PREGAME) {
            log.info("[yabranked] YAB not in PREGAME yet (attempt $attempt); retrying")
            retry.run()
            return
        }

        // run on the server thread; report the result back to the agent thread
        val configured = java.util.concurrent.CompletableFuture<Boolean>()
        server.execute {
            // card seed makes the board deterministic; goal is 13 items —
            // majority of the 25 tiles, always decided in lockout (a lines
            // goal can stalemate, which YAB itself warns about)
            val ok = applyRules(server)
            configured.complete(ok)
        }

        scheduler.execute {
            if (configured.get(30, TimeUnit.SECONDS) != true) {
                log.warn("[yabranked] config attempt $attempt failed; retrying")
                retry.run()
                return@execute
            }

            phase.set(Phase.WAITING_FOR_PLAYERS)

            readyAt = java.time.Instant.now()
            if (reporter.reportReady()) {
                log.info("[yabranked] reported ready for match ${config.matchId}")
            } else {
                log.error("[yabranked] could not report ready; players will not be sent here — shutting down")
                scheduleShutdown(server, delaySeconds = 5)
                return@execute
            }

            // Tell whoever did turn up what they are waiting for. Sitting alone in
            // a bingo lobby with no message is indistinguishable from a broken
            // match server, which is what it was reported as. Only said while
            // somebody is actually missing: telling a player their opponent has
            // not connected, while that opponent stands next to them, is worse
            // than saying nothing.
            for (at in listOf(15L, 45L)) {
                if (config.noShowTimeoutSeconds > at) {
                    scheduler.schedule({
                        if (phase.get() == Phase.WAITING_FOR_PLAYERS &&
                            !startRequested.get() &&
                            missingPlayers().isNotEmpty()
                        ) {
                            val left = config.noShowTimeoutSeconds - at
                            announce(
                                server,
                                "§eWaiting for your opponent to connect… §7the match is voided in §e${left}s§7 " +
                                    "if they do not.",
                            )
                        }
                    }, at, TimeUnit.SECONDS)
                }
            }

            // Two deadlines, because there are two failures and only one of them
            // is anybody's fault:
            //
            // - Somebody never turned up. That is the no-show, and it is decided
            //   by who has connected — not by whether YAB has got as far as
            //   starting, which is a question about this container's CPU.
            // - Everybody turned up and the game never started. That is a broken
            //   match server, and it needs its own, longer deadline; without one
            //   a game stuck in STARTING would hold both players forever, since
            //   the orchestrator's reaper only ever looks at PENDING matches.
            noShowTimer = scheduler.scheduleAtFixedRate({
                if (phase.get() != Phase.WAITING_FOR_PLAYERS) return@scheduleAtFixedRate
                val waited = java.time.Duration.between(readyAt, java.time.Instant.now()).seconds
                val missing = missingPlayers()
                when {
                    // Nobody is a no-show once the game has been told to start:
                    // everyone was here, on their teams, and YAB accepted the
                    // start. Whatever a disconnect at that point means, it is not
                    // "your opponent never connected" — and a match that was
                    // mid-STARTING has the start deadline below to end it.
                    missing.isNotEmpty() && !startRequested.get() &&
                        waited >= config.noShowTimeoutSeconds -> {
                        log.warn(
                            "[yabranked] {} did not arrive within {}s; voiding match",
                            missing.joinToString(", ") { it.name },
                            config.noShowTimeoutSeconds,
                        )
                        announce(server, "§cYour opponent never connected. §7This match is void — no rating changes.")
                        forcedOutcome.set(WireOutcome.VOID)
                        reportAndShutdown(server, WireOutcome.VOID, durationSeconds = 0)
                    }
                    // Either everybody is here, or the start was already requested
                    // and somebody has since dropped — both are "this match server
                    // never got a game going", and both still have to end, since
                    // the orchestrator's reaper only looks at PENDING matches.
                    (missing.isEmpty() || startRequested.get()) && waited >= startDeadlineSeconds -> {
                        log.error(
                            "[yabranked] game never started in {}s (start requested: {}, present: [{}], missing: [{}]); voiding",
                            waited,
                            startRequested.get(),
                            config.roster.filter { it.uuid in arrived }.joinToString(", ") { it.name },
                            missing.joinToString(", ") { it.name },
                        )
                        announce(server, "§cThis match server could not start the game. §7Void — no rating changes.")
                        forcedOutcome.set(WireOutcome.VOID)
                        reportAndShutdown(server, WireOutcome.VOID, durationSeconds = 0)
                    }
                }
            }, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun expectedPlayer(uuid: UUID): AgentConfig.ExpectedPlayer? = config.playerOf(uuid)

    /** The side index this player fights for; 0 when somehow unknown. */
    private fun sideOf(uuid: UUID): Int = config.sideOf(uuid) ?: 0

    /**
     * The outcome that follows from [side] losing.
     *
     * Two-side matches carry the winner in the outcome enum; anything wider
     * cannot, so [winningSide] is what the backend reads there. Both are set
     * together so the record and the ladder always agree.
     */
    private fun defeatOf(side: Int): WireOutcome = when {
        config.teams.size > 2 -> WireOutcome.TEAM_A_WIN
        side == 0 -> WireOutcome.TEAM_B_WIN
        else -> WireOutcome.TEAM_A_WIN
    }

    private fun onPlayerJoin(server: MinecraftServer, uuid: UUID, name: String) {
        val expected = expectedPlayer(uuid)
        if (expected == null) {
            log.warn("[yabranked] rejecting unexpected player $name ($uuid)")
            server.execute {
                server.playerList.getPlayer(uuid)?.connection
                    ?.disconnect(Component.literal("This is a private YAB Ranked match server."))
            }
            return
        }

        // Recorded whatever the phase: a rejoin mid-match is exactly the kind of
        // gap in a movement track that otherwise looks unexplained.
        replay.mark(WireReplayEventType.JOIN, expected, detail = "${expected.name} joined")

        // Arrival is what the no-show rule is about, so it is recorded the moment
        // it happens rather than inferred later from the game's state.
        arrived += uuid

        if (phase.get() != Phase.WAITING_FOR_PLAYERS) return

        log.info("[yabranked] ${expected.name} joined; assigning team")
        assignTeam(server, expected, config.teamNameOf(sideOf(uuid)), attempt = 1)
    }

    /**
     * The JOIN event fires before the player is registered in the player list,
     * so `/join <team> <player>` cannot resolve them yet. Retry until the
     * player is resolvable and the command actually succeeds.
     */
    private fun assignTeam(server: MinecraftServer, player: AgentConfig.ExpectedPlayer, team: String, attempt: Int) {
        if (attempt > MAX_ASSIGN_ATTEMPTS) {
            log.error("[yabranked] could not assign ${player.name} to $team; voiding match")
            forcedOutcome.set(WireOutcome.VOID)
            reportAndShutdown(server, WireOutcome.VOID, durationSeconds = 0)
            return
        }

        scheduler.schedule({
            server.execute {
                if (server.playerList.getPlayerByName(player.name) == null) {
                    assignTeam(server, player, team, attempt + 1)
                    return@execute
                }
                if (command(server, "join $team ${player.name}")) {
                    assignedPlayers += player.uuid
                    startWhenBothAssigned(server)
                } else {
                    assignTeam(server, player, team, attempt + 1)
                }
            }
        }, 1, TimeUnit.SECONDS)
    }

    private fun startWhenBothAssigned(server: MinecraftServer) {
        // Every player of every side, not just the two captains — a 3v3 that
        // started with four players on the field would be unrateable.
        if (config.roster.any { it.uuid !in assignedPlayers }) return
        if (!startRequested.compareAndSet(false, true)) return

        log.info("[yabranked] all ${config.roster.size} players on teams; starting game")
        // let team assignment and spawn placement settle before starting
        scheduler.schedule({ startGame(server, attempt = 1) }, 3, TimeUnit.SECONDS)
    }

    private fun startGame(server: MinecraftServer, attempt: Int) {
        if (attempt > MAX_START_ATTEMPTS) {
            log.error("[yabranked] could not start the game; voiding match")
            forcedOutcome.set(WireOutcome.VOID)
            reportAndShutdown(server, WireOutcome.VOID, durationSeconds = 0)
            return
        }
        server.execute {
            // "ignore_warnings" skips the soft checks, not the team requirement
            if (!command(server, "bingo start ignore_warnings")) {
                scheduler.schedule({ startGame(server, attempt + 1) }, 2, TimeUnit.SECONDS)
            }
        }
    }

    /**
     * `/forfeit` lets a player concede without disconnecting — otherwise the
     * only way out is to quit, which leaves the winner waiting out the
     * abandon timer before the match resolves.
     */
    private fun registerForfeitCommand(server: MinecraftServer) {
        server.commands.dispatcher.register(
            com.mojang.brigadier.builder.LiteralArgumentBuilder
                .literal<net.minecraft.commands.CommandSourceStack>("forfeit")
                .executes { context ->
                    val player = context.source.player
                    val expected = player?.let { expectedPlayer(it.uuid) }
                    when {
                        expected == null -> {
                            context.source.sendFailure(Component.literal("You are not a player in this match."))
                            0
                        }
                        phase.get() != Phase.PLAYING -> {
                            context.source.sendFailure(Component.literal("There is no match in progress."))
                            0
                        }
                        else -> {
                            // One player conceding forfeits their whole side —
                            // there is no way to keep playing a 2v2 as a 1v2.
                            val side = sideOf(expected.uuid)
                            val winners = config.opponentsOf(expected.uuid)
                            val winnerNames = winners.joinToString(", ") { it.name }
                            log.warn("[yabranked] ${expected.name} forfeited; $winnerNames win")
                            replay.mark(
                                WireReplayEventType.FORFEIT, expected,
                                detail = "${expected.name} conceded",
                            )
                            forfeiter.set(expected.uuid)
                            forcedOutcome.set(defeatOf(side))
                            winningSide.set(config.teams.indices.firstOrNull { it != side })
                            announce(server, "§c${expected.name} forfeited. §6$winnerNames win!")
                            command(server, "bingo end")
                            1
                        }
                    }
                }
        )
    }

    private fun announce(server: MinecraftServer, text: String) {
        server.execute {
            server.playerList.broadcastSystemMessage(Component.literal(text), false)
        }
    }

    /**
     * Leaving mid-match *is* conceding, and it resolves the moment it happens.
     *
     * There is no reconnect window: a player who leaves a ranked match cannot
     * rejoin it, so waiting only ever made the winner sit in a finished match
     * watching a countdown for something that could not happen — while the
     * loser, already back at the menu, could not see it at all.
     */
    private fun onPlayerDisconnect(server: MinecraftServer, uuid: UUID) {
        val leaver = expectedPlayer(uuid) ?: return
        if (phase.get() != Phase.PLAYING) {
            // Gone again before the game started: they are missing once more, so
            // the no-show deadline applies to them as if they had never arrived.
            // Without this a player who connected and immediately quit would be
            // counted as present forever, and the match would sit out the far
            // longer "the game never started" deadline instead.
            //
            // Logged because it used to not be, and it is the only thing that can
            // turn a player who is visibly on the field back into a no-show: two
            // production matches were voided as "your opponent never connected"
            // 30 seconds into YAB's terrain preload, and the log said nothing
            // about why the agent thought anybody was missing.
            val wasPresent = arrived.remove(uuid)
            if (wasPresent) {
                log.warn(
                    "[yabranked] {} disconnected before the game started (phase {}, start requested: {}); " +
                        "counted as not arrived again",
                    leaver.name,
                    phase.get(),
                    startRequested.get(),
                )
            }
            return
        }

        log.warn("[yabranked] ${leaver.name} disconnected mid-match; forfeiting immediately")
        replay.mark(WireReplayEventType.LEAVE, leaver, detail = "${leaver.name} left the match")
        resolveAbandon(server, uuid, leaver.name)
    }

    /**
     * End the match around a player who left. The disconnect event is the whole
     * story, so the leaver is excluded from the standing sides explicitly: the
     * player list may not have been updated yet at this point.
     */
    private fun resolveAbandon(server: MinecraftServer, uuid: UUID, name: String) {
        if (phase.get() != Phase.PLAYING) return
        val online = server.playerList.players.map { it.uuid }.toSet() - uuid

        // A side still has someone on the field only if at least one of its
        // players is online; awarding a win to an empty server is the bug the
        // void case exists for.
        val standing = config.teams.indices.filter { side ->
            config.teams[side].any { it.uuid in online }
        }
        val leaverSide = sideOf(uuid)
        val winner = standing.singleOrNull()
        if (winner == null || winner == leaverSide) {
            log.warn("[yabranked] nobody left to award the win to; voiding match")
            forcedOutcome.set(WireOutcome.VOID)
        } else {
            val winnerNames = config.teams[winner].joinToString(", ") { it.name }
            log.warn("[yabranked] $name left the match; $winnerNames win by forfeit")
            forfeiter.set(uuid)
            forcedOutcome.set(defeatOf(leaverSide))
            winningSide.set(winner)
            announce(server, "§6$winnerNames win by forfeit — §7$name left the match.")
        }
        server.execute { command(server, "bingo end") }
        // onGameEnded picks up forcedOutcome from here
    }

    private fun teamScore(playerUuid: UUID): Int {
        val api = BingoApi.INSTANCE ?: return 0
        return api.teams.firstOrNull { playerUuid in it.players }?.score?.items ?: 0
    }

    /** Every side's score, in the backend's side order. */
    private fun teamScores(): List<Int> =
        config.teams.map { side -> side.firstOrNull()?.let { teamScore(it.uuid) } ?: 0 }

    private fun onGameEnded(event: GameEndedEvent) {
        val server = this.server ?: return
        if (!phase.compareAndSet(Phase.PLAYING, Phase.REPORTED)) {
            // game ended without ever reaching PLAYING (e.g. voided) — no-show path reports itself
            return
        }

        val outcome = forcedOutcome.get() ?: run {
            val winnerPlayers = event.winningTeam?.players.orEmpty()
            // YAB reports the winning team as a set of players; map it back to
            // the side the backend knows, which is what the ladder is keyed on.
            val side = config.teams.indices.firstOrNull { index ->
                config.teams[index].any { it.uuid in winnerPlayers }
            }
            if (side == null) WireOutcome.DRAW else {
                winningSide.set(side)
                if (side == 0) WireOutcome.TEAM_A_WIN else WireOutcome.TEAM_B_WIN
            }
        }

        reportAndShutdown(
            server = server,
            outcome = outcome,
            durationSeconds = event.duration?.seconds ?: 0,
        )
    }

    private fun reportAndShutdown(server: MinecraftServer, outcome: WireOutcome, durationSeconds: Long) {
        replay.stop()
        replay.mark(WireReplayEventType.GAME_END, detail = "Match ended: $outcome")
        val recording = replay.build(durationSeconds)
        val report = WireResultReport(
            matchId = config.matchId,
            outcome = outcome,
            durationSeconds = durationSeconds,
            teamAScore = teamScore(config.playerA.uuid),
            teamBScore = teamScore(config.playerB.uuid),
            forfeitedBy = forfeiter.get()?.toString(),
            // Only sent for matches the two-side view cannot describe; a 1v1
            // report stays byte-for-byte what it always was.
            winningTeam = winningSide.get().takeIf { config.teams.size > 2 },
            teamScores = if (config.teams.size > 2) teamScores() else emptyList(),
        )
        log.info("[yabranked] reporting result: $report")

        // report from the scheduler thread — never block the server thread on HTTP
        scheduler.execute {
            // Replay first, result second, and the order is load-bearing.
            //
            // Settling a match is what fires the orchestrator's teardown, and
            // teardown is `docker rm -f` on this very container. Uploading
            // afterwards means racing our own destruction, which the container
            // reliably loses: the recording was built, logged as sent, and never
            // arrived, so every player pressing "Save replay" was told there was
            // nothing to save. Nothing about the upload needs a settled match —
            // the row exists from creation and the route only checks the token —
            // so it goes first, where the container is still guaranteed alive.
            //
            // The recorder's own checkpoints cover the settles this path never
            // sees at all, i.e. a match ended over a player's bearer token.
            if (recording != null && !replay.flush(recording)) {
                log.warn("[yabranked] replay upload failed; the match itself is unaffected")
            }
            if (!reporter.reportResult(report)) {
                log.error("[yabranked] FAILED to deliver result after retries; container logs are the evidence")
            }
            endSessionAndShutdown(server)
        }
    }

    /**
     * End the session the moment the result is in: send everyone back to their
     * ranked client, then halt.
     *
     * There is deliberately **no linger on the match server**. Two reasons, and
     * they point the same way:
     *
     * - **The postgame screen is a trap, not a feature.** This is still a *YAB*
     *   server. YAB's own POSTGAME ends the instant a surviving player presses
     *   ready — "return to lobby" — and ending it resets the worlds and drops
     *   everyone into a fresh bingo lobby game on a new team. A ranked match that
     *   hands its players a lobby game is broken, and no linger short enough to
     *   be safe is long enough to be useful: the button is there for as long as
     *   the window is.
     * - **The result screen hangs off `DISCONNECT`.** `YabRankedClient.matchEnded`
     *   is only reached when the connection drops, so every second spent lingering
     *   was a second the player sat on YAB's screen waiting for the ranked result
     *   that is the actual point of a ranked match.
     *
     * The recording is already safe by the time this runs — `replay.flush` is
     * synchronous and completes before the result report — so halting promptly
     * costs nothing. [AgentConfig.postgameSeconds] now bounds only the last few
     * seconds between the disconnect and the halt, and the orchestrator's
     * `settleGrace` is derived from it, so the container still outlives the
     * process rather than being killed mid-write.
     */
    private fun endSessionAndShutdown(server: MinecraftServer) {
        log.info("[yabranked] match over; returning players to their ranked client")
        announce(server, "§6Match complete! §7Returning you to the ranked menu.")
        // Grace, not a linger: long enough for the announce to land and the
        // disconnect packets to flush, short enough that nobody can press ready.
        scheduleShutdown(server, delaySeconds = config.postgameSeconds.coerceIn(1, MAX_WIND_DOWN_SECONDS))
    }

    private fun scheduleShutdown(server: MinecraftServer, delaySeconds: Long) {
        scheduler.schedule({
            log.info("[yabranked] shutting down match server")
            // Disconnected with a reason, rather than dropped when the socket
            // dies with the process: the client's whole wind-down — result
            // screen, queue button, "you are no longer in a match" — hangs off
            // DISCONNECT, and a killed connection makes it vanilla's
            // "Connection lost" instead.
            server.execute {
                for (player in server.playerList.players.toList()) {
                    player.connection.disconnect(Component.literal("Match complete — see your result in the Ranked menu."))
                }
            }
            scheduler.schedule({ server.halt(false) }, 1, TimeUnit.SECONDS)
        }, delaySeconds, TimeUnit.SECONDS)
    }
}
