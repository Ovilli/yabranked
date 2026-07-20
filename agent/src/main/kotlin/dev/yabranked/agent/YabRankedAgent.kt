package dev.yabranked.agent

import me.jfenn.bingo.api.BingoApi
import me.jfenn.bingo.api.BingoEvents
import me.jfenn.bingo.api.event.GameEndedEvent
import net.fabricmc.api.DedicatedServerModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
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
    }

    private lateinit var config: AgentConfig
    private lateinit var reporter: BackendReporter
    private var server: MinecraftServer? = null

    private val phase = AtomicReference(Phase.CONFIGURING)
    private var noShowTimer: ScheduledFuture<*>? = null
    private var abandonTimer: ScheduledFuture<*>? = null

    /** Set when the agent decides the outcome itself (abandon/no-show). */
    private val forcedOutcome = AtomicReference<WireOutcome?>(null)

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

        ServerLifecycleEvents.SERVER_STARTED.register { server -> onServerStarted(server) }

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            onPlayerJoin(server, handler.player.uuid, handler.player.gameProfile.name)
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            onPlayerDisconnect(server, handler.player.uuid)
        }

        BingoEvents.GAME_STARTED.register { _ ->
            log.info("[yabranked] game started")
            phase.set(Phase.PLAYING)
            noShowTimer?.cancel(false)
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

            if (reporter.reportReady()) {
                log.info("[yabranked] reported ready for match ${config.matchId}")
            } else {
                log.error("[yabranked] could not report ready; players will not be sent here — shutting down")
                scheduleShutdown(server, delaySeconds = 5)
                return@execute
            }

            noShowTimer = scheduler.schedule({
                if (phase.get() == Phase.WAITING_FOR_PLAYERS) {
                    log.warn("[yabranked] players did not arrive within ${config.noShowTimeoutSeconds}s; voiding match")
                    forcedOutcome.set(WireOutcome.VOID)
                    reportAndShutdown(server, WireOutcome.VOID, durationSeconds = 0)
                }
            }, config.noShowTimeoutSeconds, TimeUnit.SECONDS)
        }
    }

    private fun expectedPlayer(uuid: UUID): AgentConfig.ExpectedPlayer? = when (uuid) {
        config.playerA.uuid -> config.playerA
        config.playerB.uuid -> config.playerB
        else -> null
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

        // returning player cancels the abandon countdown
        abandonTimer?.cancel(false)

        if (phase.get() != Phase.WAITING_FOR_PLAYERS) return

        log.info("[yabranked] ${expected.name} joined; assigning team")
        val team = if (uuid == config.playerA.uuid) "red" else "blue"
        assignTeam(server, expected, team, attempt = 1)
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
        if (config.playerA.uuid !in assignedPlayers || config.playerB.uuid !in assignedPlayers) return
        if (!startRequested.compareAndSet(false, true)) return

        log.info("[yabranked] both players on teams; starting game")
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
                            val opponent =
                                if (expected.uuid == config.playerA.uuid) config.playerB else config.playerA
                            log.warn("[yabranked] ${expected.name} forfeited; ${opponent.name} wins")
                            forcedOutcome.set(
                                if (expected.uuid == config.playerA.uuid) WireOutcome.TEAM_B_WIN
                                else WireOutcome.TEAM_A_WIN
                            )
                            announce(server, "§c${expected.name} forfeited. §6${opponent.name} wins!")
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

    private fun onPlayerDisconnect(server: MinecraftServer, uuid: UUID) {
        val leaver = expectedPlayer(uuid) ?: return
        if (phase.get() != Phase.PLAYING) return

        val opponent = if (uuid == config.playerA.uuid) config.playerB else config.playerA
        val forfeitSeconds = config.forfeitSeconds
        log.warn("[yabranked] ${leaver.name} disconnected mid-match; forfeit in ${forfeitSeconds}s unless they return")

        // Tell the player still in the match what is happening — otherwise the
        // wait looks like the server simply stopped responding.
        announce(server, "§c${leaver.name} disconnected. §7You win by forfeit in §e${forfeitSeconds}s§7 if they don't return.")
        for (remaining in listOf(60L, 15L)) {
            if (forfeitSeconds > remaining) {
                scheduler.schedule(
                    {
                        if (phase.get() == Phase.PLAYING && server.playerList.getPlayer(uuid) == null) {
                            announce(server, "§7Forfeit in §e${remaining}s§7...")
                        }
                    },
                    forfeitSeconds - remaining,
                    TimeUnit.SECONDS,
                )
            }
        }

        abandonTimer = scheduler.schedule({
            if (phase.get() != Phase.PLAYING) return@schedule
            val online = server.playerList.players.map { it.uuid }.toSet()
            if (uuid in online) return@schedule // they came back

            if (opponent.uuid !in online) {
                // both players are gone — there is no one to award the win to
                log.warn("[yabranked] both players disconnected; voiding match")
                forcedOutcome.set(WireOutcome.VOID)
            } else {
                log.warn("[yabranked] ${leaver.name} did not return; ${opponent.name} wins by forfeit")
                forcedOutcome.set(
                    if (uuid == config.playerA.uuid) WireOutcome.TEAM_B_WIN else WireOutcome.TEAM_A_WIN
                )
                announce(server, "§6${opponent.name} wins by forfeit — §7${leaver.name} did not return.")
            }
            server.execute { command(server, "bingo end") }
            // onGameEnded picks up forcedOutcome from here
        }, forfeitSeconds, TimeUnit.SECONDS)
    }

    private fun teamScore(playerUuid: UUID): Int {
        val api = BingoApi.INSTANCE ?: return 0
        return api.teams.firstOrNull { playerUuid in it.players }?.score?.items ?: 0
    }

    private fun onGameEnded(event: GameEndedEvent) {
        val server = this.server ?: return
        if (!phase.compareAndSet(Phase.PLAYING, Phase.REPORTED)) {
            // game ended without ever reaching PLAYING (e.g. voided) — no-show path reports itself
            return
        }

        val outcome = forcedOutcome.get() ?: run {
            val winnerPlayers = event.winningTeam?.players.orEmpty()
            when {
                config.playerA.uuid in winnerPlayers -> WireOutcome.TEAM_A_WIN
                config.playerB.uuid in winnerPlayers -> WireOutcome.TEAM_B_WIN
                else -> WireOutcome.DRAW
            }
        }

        reportAndShutdown(
            server = server,
            outcome = outcome,
            durationSeconds = event.duration?.seconds ?: 0,
        )
    }

    private fun reportAndShutdown(server: MinecraftServer, outcome: WireOutcome, durationSeconds: Long) {
        val report = WireResultReport(
            matchId = config.matchId,
            outcome = outcome,
            durationSeconds = durationSeconds,
            teamAScore = teamScore(config.playerA.uuid),
            teamBScore = teamScore(config.playerB.uuid),
        )
        log.info("[yabranked] reporting result: $report")

        // report from the scheduler thread — never block the server thread on HTTP
        scheduler.execute {
            if (!reporter.reportResult(report)) {
                log.error("[yabranked] FAILED to deliver result after retries; container logs are the evidence")
            }
            // Keep the server up so players can read YAB's game-over screen
            // (scored items, times, winner) instead of being kicked mid-celebration.
            lingerThenShutdown(server)
        }
    }

    /**
     * Post-match linger: players stay on the server with the results screen up
     * and are warned before the server closes, so the disconnect is never a
     * surprise. They leave whenever they want; the container dies either way.
     */
    private fun lingerThenShutdown(server: MinecraftServer) {
        val linger = config.postgameSeconds
        log.info("[yabranked] match over; keeping server up for ${linger}s so players can review the results")

        announce(server, "§6Match complete! §7Results are on screen — queue again from the Ranked menu when you're ready.")

        // warn at 60s and 15s remaining (only those that fit inside the window)
        for (remaining in listOf(60L, 15L)) {
            if (linger > remaining) {
                scheduler.schedule(
                    { announce(server, "§7This match server closes in §e${remaining}s§7.") },
                    linger - remaining,
                    TimeUnit.SECONDS,
                )
            }
        }

        scheduleShutdown(server, delaySeconds = linger)
    }

    private fun scheduleShutdown(server: MinecraftServer, delaySeconds: Long) {
        scheduler.schedule({
            log.info("[yabranked] shutting down match server")
            server.halt(false)
        }, delaySeconds, TimeUnit.SECONDS)
    }
}
