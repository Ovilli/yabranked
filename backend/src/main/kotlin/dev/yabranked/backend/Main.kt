package dev.yabranked.backend

import dev.yabranked.backend.api.ApiDependencies
import dev.yabranked.backend.api.rankedApi
import dev.yabranked.backend.auth.FakeSessionVerifier
import dev.yabranked.backend.auth.MojangSessionVerifier
import dev.yabranked.backend.config.BackendConfig
import dev.yabranked.backend.config.ConfigException
import dev.yabranked.backend.match.MatchService
import dev.yabranked.backend.ops.GracefulShutdown
import dev.yabranked.backend.ops.Metrics
import dev.yabranked.backend.ops.ReadinessChecks
import dev.yabranked.backend.ops.databaseProbe
import dev.yabranked.backend.ops.healthRoutes
import dev.yabranked.backend.ops.metricsRoutes
import dev.yabranked.backend.ops.requestLogging
import dev.yabranked.backend.orchestrator.ContainerLimits
import dev.yabranked.backend.orchestrator.DockerCliRuntime
import dev.yabranked.backend.orchestrator.MatchOrchestrator
import dev.yabranked.backend.orchestrator.OrchestratorConfig
import dev.yabranked.backend.queue.MatchmakingQueue
import dev.yabranked.backend.rating.DecaySweep
import dev.yabranked.backend.season.SeasonRollover
import dev.yabranked.backend.season.SeasonService
import dev.yabranked.backend.store.Database
import dev.yabranked.backend.store.AchievementStore
import dev.yabranked.backend.store.InMemoryAchievementStore
import dev.yabranked.backend.store.InMemoryEndorsementStore
import dev.yabranked.backend.store.InMemoryFriendStore
import dev.yabranked.backend.store.InMemoryModeStatsStore
import dev.yabranked.backend.store.InMemoryReportStore
import dev.yabranked.backend.store.EndorsementStore
import dev.yabranked.backend.store.FriendStore
import dev.yabranked.backend.store.ModeStatsStore
import dev.yabranked.backend.store.PostgresEndorsementStore
import dev.yabranked.backend.store.PostgresFriendStore
import dev.yabranked.backend.store.PostgresModeStatsStore
import dev.yabranked.backend.store.LockingTransactionRunner
import dev.yabranked.backend.store.PostgresAchievementStore
import dev.yabranked.backend.store.PostgresTransactionRunner
import dev.yabranked.backend.store.TransactionRunner
import dev.yabranked.backend.store.MatchStore
import dev.yabranked.backend.store.PlayerStore
import dev.yabranked.backend.store.PostgresMatchStore
import dev.yabranked.backend.store.PostgresPlayerStore
import dev.yabranked.backend.store.PostgresReportStore
import dev.yabranked.backend.store.PostgresSettingsStore
import dev.yabranked.backend.store.ReportStore
import dev.yabranked.backend.store.StoreDispatchers
import dev.yabranked.backend.queue.QueueService
import dev.yabranked.backend.rating.EloRatingSystem
import dev.yabranked.backend.store.InMemoryMatchStore
import dev.yabranked.backend.store.InMemoryPlayerStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineDispatcher
import org.slf4j.LoggerFactory

fun main(args: Array<String>) {
    val log = LoggerFactory.getLogger("yabranked")

    // Everything is parsed and validated here, before anything is opened: a bad
    // value should stop the process, not surface as a mystery three hours in.
    val config = try {
        BackendConfig.fromEnv(args)
    } catch (e: ConfigException) {
        log.error("invalid configuration: {}", e.message)
        kotlin.system.exitProcess(1)
    }

    // Fake auth mints a session for any username and opens the debug endpoint
    // that hands out match server tokens. Pointed at a real database that is
    // account takeover for every player on the ladder, so refuse the pair
    // unless someone says out loud that the database is disposable.
    if (config.fakeAuth && config.usesPostgres &&
        System.getenv("YABRANKED_ALLOW_FAKE_AUTH_WITH_DATABASE") != "1"
    ) {
        log.error(
            "refusing to start: fake auth against a database lets anyone sign in as anyone. " +
                "Drop YABRANKED_FAKE_AUTH/--fake-auth, or set " +
                "YABRANKED_ALLOW_FAKE_AUTH_WITH_DATABASE=1 if this database is throwaway."
        )
        kotlin.system.exitProcess(1)
    }

    val shutdown = GracefulShutdown()
    val metrics = Metrics()

    val players: PlayerStore
    val matches: MatchStore
    val reports: ReportStore
    val replays: dev.yabranked.backend.store.ReplayStore
    val achievements: AchievementStore
    val friendStore: FriendStore
    val endorsementStore: EndorsementStore
    val modeStats: ModeStatsStore
    val seasons: SeasonService
    val transactions: TransactionRunner
    // Store calls are blocking JDBC; handlers hop here instead of running them
    // on Ktor's event loop. Bounded by the pool: queueing beyond it would only
    // move the wait inside Hikari.
    val storeDispatcher: CoroutineDispatcher
    // null on in-memory stores — readiness has no database to probe.
    var database: Database? = null

    if (config.databaseUrl != null) {
        val db = Database(
            url = config.databaseUrl,
            user = config.databaseUser,
            password = config.databasePassword,
            poolSize = config.dbPoolSize,
            connectionTimeoutMs = config.dbConnectionTimeoutMs,
        )
        database = db
        db.migrate()
        storeDispatcher = StoreDispatchers.bounded(config.dbPoolSize)
        log.info(
            "using Postgres at {} (pool size {})",
            config.databaseUrl.substringBefore('?'), config.dbPoolSize,
        )

        val settings = PostgresSettingsStore(db)
        players = PostgresPlayerStore(db)
        matches = PostgresMatchStore(db)
        reports = PostgresReportStore(db)
        replays = dev.yabranked.backend.store.PostgresReplayStore(db)
        achievements = PostgresAchievementStore(db)
        friendStore = PostgresFriendStore(db)
        endorsementStore = PostgresEndorsementStore(db)
        modeStats = PostgresModeStatsStore(db)
        transactions = PostgresTransactionRunner(db)
        seasons = SeasonService(
            initialSeason = config.season
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
        replays = dev.yabranked.backend.store.InMemoryReplayStore()
        achievements = InMemoryAchievementStore()
        friendStore = InMemoryFriendStore()
        endorsementStore = InMemoryEndorsementStore()
        modeStats = InMemoryModeStatsStore()
        transactions = LockingTransactionRunner()
        seasons = SeasonService(config.season ?: 1)
        storeDispatcher = StoreDispatchers.default
    }

    // Dev fixture: a fake competitive scene so the ranked UI can be reviewed
    // without playing real matches. In-memory only, to never touch a real DB.
    if (config.seed) {
        if (config.usesPostgres) {
            log.warn("YABRANKED_SEED ignored: refusing to seed a Postgres database")
        } else {
            dev.yabranked.backend.dev.Seeder.seed(
                players, matches, seasons.currentSeason,
                selfName = config.seedMe,
                achievements = achievements,
            )
            log.warn("seeded in-memory stores with fixture leaderboard/match data (YABRANKED_SEED=1)")
        }
    }

    val rating = EloRatingSystem()
    val matchService = MatchService(
        players, matches, rating, seasons,
        achievements = achievements,
        transactions = transactions,
        modeStats = modeStats,
    )
    val queueService = QueueService(
        MatchmakingQueue(), matchService,
        storeDispatcher = storeDispatcher,
        metrics = metrics,
    )

    val verifier = if (config.fakeAuth) {
        log.warn("!! fake auth enabled — do not expose this instance publicly")
        FakeSessionVerifier()
    } else {
        MojangSessionVerifier(HttpClient(CIO))
    }

    matchService.onMatchCreated { record ->
        metrics.matchCreated()
        log.info(
            "match created: {} ({} vs {}) worldSeed={} cardSeed={}",
            record.id, record.playerA, record.playerB,
            record.settings.worldSeed, record.settings.cardSeed,
        )
    }
    matchService.onMatchSettled { record -> metrics.matchSettled(record.outcome) }

    // Orchestration: provision one Docker match server per match.
    // Without it (local dev), matches are marked active immediately with a
    // placeholder address so the queue flow stays testable.
    val orchestrator = if (config.orchestrate) {
        MatchOrchestrator(
            config = OrchestratorConfig(
                image = config.matchImage,
                publicHost = config.publicHost,
                backendUrlForAgents = config.backendUrlForAgents,
                onlineMode = config.onlineMode,
                hostNetwork = config.hostNetwork,
                limits = ContainerLimits(memory = config.matchMemory, cpus = config.matchCpus),
                noShowTimeoutSeconds = config.noShowTimeoutSeconds,
                postgameSeconds = config.postgameSeconds,
            ),
            runtime = DockerCliRuntime(),
            matchService = matchService,
            matches = matches,
            players = players,
            metrics = metrics,
        )
    } else {
        log.warn("orchestration disabled (set YABRANKED_ORCHESTRATE=1); matches get placeholder servers")
        matchService.onMatchCreated { record ->
            matchService.setServerAddress(record.id, "pending.invalid:25565")
            matchService.markReady(record.id.toString(), record.serverToken)
        }
        null
    }

    // Clear anything the previous process left behind before taking new matches.
    orchestrator?.reconcile()

    val readiness = ReadinessChecks(
        matchmakingRunning = { queueService.isRunning },
        draining = { shutdown.isDraining },
        databaseReachable = database?.let { databaseProbe(it.dataSource) },
    )

    // Keeps the visible top of the ladder honest: without it an inactive
    // top-ten player holds their rank by never queueing again.
    val decaySweep = DecaySweep(players, seasons, rating, transactions = transactions)
    // Recordings are large and every match makes one, so the default is that
    // they expire; the store decides what a save or a report keeps alive.
    val replayPolicy = dev.yabranked.backend.store.ReplayPolicy()
    // The packets themselves never go in the database; see ReplayBlobStores.kt.
    // No directory configured means recordings live for as long as the process
    // does, which is the same bargain the in-memory stores make.
    // Object storage first, then a local disk, then memory. The order is the
    // order of durability, and the last one is a fallback that says so.
    val replayBlobs = dev.yabranked.backend.store.S3ReplayBlobStore.create(
        endpoint = config.replayS3Endpoint,
        bucket = config.replayS3Bucket,
        accessKey = config.replayS3AccessKey,
        secretKey = config.replayS3SecretKey,
        region = config.replayS3Region,
    )?.also { log.info("replay packet data in S3 bucket {}", config.replayS3Bucket) }
        ?: config.replayDir
            ?.let { dev.yabranked.backend.store.FileReplayBlobStore(java.nio.file.Path.of(it)) }
            ?: dev.yabranked.backend.store.InMemoryReplayBlobStore().also {
                log.warn(
                    "no YABRANKED_REPLAY_DIR or YABRANKED_REPLAY_S3_BUCKET set — replay packet data is in " +
                        "memory, capped, and lost on restart"
                )
            }
    val replaySweep = dev.yabranked.backend.store.ReplaySweep(replays, replayBlobs)

    val server = embeddedServer(Netty, port = config.port) {
        queueService.start(this)
        decaySweep.start(this)
        replaySweep.start(this)
        orchestrator?.start(this)
        requestLogging()
        metricsRoutes(metrics)
        healthRoutes(readiness)
        rankedApi(
            ApiDependencies(
                verifier = verifier,
                players = players,
                matches = matches,
                matchService = matchService,
                queueService = queueService,
                debugEndpoints = config.fakeAuth,
                minClientVersion = config.minClientVersion,
                seasons = seasons,
                reports = reports,
                replays = replays,
                replayBlobs = replayBlobs,
                replayPolicy = replayPolicy,
                achievements = achievements,
                adminToken = config.adminToken,
                rollover = SeasonRollover(players, rating),
                storeDispatcher = storeDispatcher,
                friendStore = friendStore,
                endorsementStore = endorsementStore,
            )
        )
    }

    // Order matters: stop matching before anyone is told anything, let readiness
    // go red so traffic drains away, and only then close what holds resources.
    shutdown.step("matchmaking") { queueService.stop() }
    shutdown.step("decay") { decaySweep.stop() }
    shutdown.step("replays") { replaySweep.stop() }
    shutdown.step("http") {
        server.stop(gracePeriodMillis = config.shutdownGraceSeconds * 1000, timeoutMillis = config.shutdownGraceSeconds * 1000)
    }
    shutdown.step("metrics") { metrics.close() }
    shutdown.step("database") { database?.close() }
    shutdown.installHook()

    server.start(wait = true)
    // A normal `stop` (rather than SIGTERM) still has to release the pool.
    // Idempotent, so racing the hook is harmless.
    shutdown.drain()
}
