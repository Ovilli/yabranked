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

    private lateinit var config: AgentConfig
    private lateinit var reporter: BackendReporter
    private var server: MinecraftServer? = null

    private val phase = AtomicReference(Phase.CONFIGURING)
    private var noShowTimer: ScheduledFuture<*>? = null
    private var abandonTimer: ScheduledFuture<*>? = null

    /** Set when the agent decides the outcome itself (abandon/no-show). */
    private val forcedOutcome = AtomicReference<WireOutcome?>(null)

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

    private fun command(server: MinecraftServer, command: String) {
        log.info("[yabranked] > /$command")
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), command)
    }

    private fun onServerStarted(server: MinecraftServer) {
        this.server = server

        // configure the ranked match; the card seed makes the board deterministic
        command(server, "bingo mode lockout true")
        // majority of the 25 tiles — lockout with a lines goal can stalemate,
        // 13 items is always decided (or a draw on time limit, which Elo handles)
        command(server, "bingo goal 13 items")
        command(server, "bingo options end_when first_win")
        command(server, "bingo timelimit ${config.timeLimitMinutes}")
        command(server, "bingo card seed ${config.cardSeed}")

        phase.set(Phase.WAITING_FOR_PLAYERS)

        if (reporter.reportReady()) {
            log.info("[yabranked] reported ready for match ${config.matchId}")
        } else {
            log.error("[yabranked] could not report ready; players will not be sent here — shutting down")
            scheduleShutdown(server, delaySeconds = 5)
            return
        }

        noShowTimer = scheduler.schedule({
            if (phase.get() == Phase.WAITING_FOR_PLAYERS) {
                log.warn("[yabranked] players did not arrive within ${config.noShowTimeoutSeconds}s; voiding match")
                forcedOutcome.set(WireOutcome.VOID)
                reportAndShutdown(server, WireOutcome.VOID, durationSeconds = 0)
            }
        }, config.noShowTimeoutSeconds, TimeUnit.SECONDS)
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

        val online = server.playerList.players.map { it.uuid }.toSet()
        if (config.playerA.uuid in online && config.playerB.uuid in online) {
            log.info("[yabranked] both players present; assigning teams and starting")
            server.execute {
                command(server, "join red ${config.playerA.name}")
                command(server, "join blue ${config.playerB.name}")
            }
            // small delay so team assignment and spawn placement settle before start
            scheduler.schedule({
                server.execute {
                    command(server, "bingo start ignore_warnings")
                }
            }, 3, TimeUnit.SECONDS)
        }
    }

    private fun onPlayerDisconnect(server: MinecraftServer, uuid: UUID) {
        val leaver = expectedPlayer(uuid) ?: return
        if (phase.get() != Phase.PLAYING) return

        log.warn("[yabranked] ${leaver.name} disconnected mid-match; forfeit in 120s unless they return")
        abandonTimer = scheduler.schedule({
            val online = server.playerList.players.map { it.uuid }.toSet()
            if (uuid !in online && phase.get() == Phase.PLAYING) {
                val winner = if (uuid == config.playerA.uuid) WireOutcome.TEAM_B_WIN else WireOutcome.TEAM_A_WIN
                log.warn("[yabranked] ${leaver.name} did not return; opponent wins by forfeit")
                forcedOutcome.set(winner)
                server.execute { command(server, "bingo end") }
                // onGameEnded picks up forcedOutcome from here
            }
        }, 120, TimeUnit.SECONDS)
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
            // leave the server up briefly so players can see the results screen
            scheduleShutdown(server, delaySeconds = 30)
        }
    }

    private fun scheduleShutdown(server: MinecraftServer, delaySeconds: Long) {
        scheduler.schedule({
            log.info("[yabranked] shutting down match server")
            server.halt(false)
        }, delaySeconds, TimeUnit.SECONDS)
    }
}
