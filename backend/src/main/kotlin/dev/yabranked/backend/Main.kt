package dev.yabranked.backend

import dev.yabranked.backend.api.ApiDependencies
import dev.yabranked.backend.api.rankedApi
import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.auth.MojangSessionVerifier
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.orchestrator.DockerCliRuntime
import dev.yabranked.backend.orchestrator.MatchOrchestrator
import dev.yabranked.backend.orchestrator.OrchestratorConfig
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.Database
import dev.yabranked.backend.store.InMemoryReportStore
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.PostgresMatchStore
import dev.yabranked.backend.store.PostgresPlayerStore
import dev.yabranked.backend.store.PostgresReportStore
import dev.yabranked.backend.store.PostgresSettingsStore
import dev.yabranked.backend.store.ReportStore
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("yabranked")

    val port = System.getenv("YABRANKED_PORT")?.toIntOrNull() ?: 8080
    // --fake-auth: accept any username without Mojang verification (local dev / mock client)
    val fakeAuth = "--fake-auth" in args || System.getenv("YABRANKED_FAKE_AUTH") == "1"

    // Postgres when YABRANKED_DATABASE_URL is set; in-memory otherwise (local dev).
    val databaseUrl = System.getenv("YABRANKED_DATABASE_URL")
    val envSeason = System.getenv("YABRANKED_SEASON")?.toIntOrNull()

    val players: PlayerStore
    val matches: MatchStore
    val reports: ReportStore
    val seasons: SeasonService

    if (databaseUrl != null) {
        val db = Database(
            url = databaseUrl,
            user = System.getenv("YABRANKED_DATABASE_USER"),
            password = System.getenv("YABRANKED_DATABASE_PASSWORD"),
        )
        db.migrate()
        log.info("using Postgres at {}", databaseUrl.substringBefore('?'))

        val settings = PostgresSettingsStore(db)
        players = PostgresPlayerStore(db)
        matches = PostgresMatchStore(db)
        reports = PostgresReportStore(db)
        seasons = SeasonService(
            initialSeason = envSeason
                ?: settings.get("current_season")?.toIntOrNull()
                ?: 1,
            onChange = { settings.put("current_season", it.toString()) },
        )
        // remember an env-forced season too
        settings.put("current_season", seasons.currentSeason.toString())
    } else {
        log.warn("no YABRANKED_DATABASE_URL set — using in-memory stores (state lost on restart)")
        players = InMemoryPlayerStore()
        matches = InMemoryMatchStore()
        reports = InMemoryReportStore()
        seasons = SeasonService(envSeason ?: 1)
    }

    val rating = EloRatingSystem()
    val matchService = MatchService(players, matches, rating, seasons)
    val queueService = QueueService(MatchmakingQueue(), matchService)

    val verifier = if (fakeAuth) {
        log.warn("!! fake auth enabled — do not expose this instance publicly")
        FakeSessionVerifier()
    } else {
        MojangSessionVerifier(HttpClient(CIO))
    }

    matchService.onMatchCreated { record ->
        log.info(
            "match created: {} ({} vs {}) worldSeed={} cardSeed={}",
            record.id, record.playerA, record.playerB,
            record.settings.worldSeed, record.settings.cardSeed,
        )
    }

    // Orchestration: provision one Docker match server per match.
    // Without it (local dev), matches are marked active immediately with a
    // placeholder address so the queue flow stays testable.
    val orchestrate = System.getenv("YABRANKED_ORCHESTRATE") == "1"
    val orchestrator = if (orchestrate) {
        MatchOrchestrator(
            config = OrchestratorConfig(
                image = System.getenv("YABRANKED_MATCH_IMAGE") ?: "yabranked-match:latest",
                publicHost = System.getenv("YABRANKED_PUBLIC_HOST") ?: "localhost",
                backendUrlForAgents = System.getenv("YABRANKED_BACKEND_URL_FOR_AGENTS")
                    ?: if (System.getenv("YABRANKED_HOST_NETWORK") == "false") {
                        "http://host.docker.internal:$port"
                    } else {
                        "http://localhost:$port"
                    },
                onlineMode = System.getenv("YABRANKED_ONLINE_MODE") != "false",
                hostNetwork = System.getenv("YABRANKED_HOST_NETWORK") != "false",
                noShowTimeoutSeconds = System.getenv("YABRANKED_NO_SHOW_TIMEOUT_SECONDS")?.toLongOrNull(),
                postgameSeconds = System.getenv("YABRANKED_POSTGAME_SECONDS")?.toLongOrNull(),
            ),
            runtime = DockerCliRuntime(),
            matchService = matchService,
            matches = matches,
            players = players,
        )
    } else {
        log.warn("orchestration disabled (set YABRANKED_ORCHESTRATE=1); matches get placeholder servers")
        matchService.onMatchCreated { record ->
            matchService.setServerAddress(record.id, "pending.invalid:25565")
            matchService.markReady(record.id.toString(), record.serverToken)
        }
        null
    }

    embeddedServer(Netty, port = port) {
        queueService.start(this)
        orchestrator?.start(this)
        rankedApi(
            ApiDependencies(
                verifier = verifier,
                players = players,
                matches = matches,
                matchService = matchService,
                queueService = queueService,
                debugEndpoints = fakeAuth,
                minClientVersion = System.getenv("YABRANKED_MIN_CLIENT_VERSION"),
                seasons = seasons,
                reports = reports,
                adminToken = System.getenv("YABRANKED_ADMIN_TOKEN"),
            )
        )
    }.start(wait = true)
}
