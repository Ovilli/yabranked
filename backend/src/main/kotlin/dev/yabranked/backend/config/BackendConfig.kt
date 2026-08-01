package dev.yabranked.backend.config

import dev.yabranked.backend.store.Database

/** A setting was present but unusable; startup aborts on one of these. */
class ConfigException(message: String) : RuntimeException(message)

/** Where a setting comes from; a plain lambda so tests need no real environment. */
typealias Env = (String) -> String?

/**
 * Every setting the backend reads, parsed and validated once at startup.
 *
 * Parsing used to be spread through `main` with two boolean conventions in
 * play — `YABRANKED_ORCHESTRATE` was true only for "1" while
 * `YABRANKED_ONLINE_MODE` was true unless "false", so `ONLINE_MODE=0` turned
 * online mode *on*. Everything now goes through [bool], which accepts the
 * obvious spellings of both and rejects anything else rather than guessing.
 */
data class BackendConfig(
    val port: Int,
    /** Accept any username without Mojang verification, and expose the debug routes. */
    val fakeAuth: Boolean,
    val databaseUrl: String?,
    val databaseUser: String?,
    val databasePassword: String?,
    /** Also bounds the store dispatcher: queueing past the pool only moves the wait. */
    val dbPoolSize: Int,
    val dbConnectionTimeoutMs: Long,
    /** Forces the current season instead of continuing the persisted one. */
    val season: Int?,
    val seed: Boolean,
    val seedMe: String?,
    val minClientVersion: String?,
    val adminToken: String?,
    val orchestrate: Boolean,
    val matchImage: String,
    val publicHost: String,
    /** Already resolved: the explicit setting, or one derived from [hostNetwork]. */
    val backendUrlForAgents: String,
    val hostNetwork: Boolean,
    val onlineMode: Boolean,
    val noShowTimeoutSeconds: Long?,
    val postgameSeconds: Long?,
    /** Docker `--memory` value for a match container; null leaves it unlimited. */
    val matchMemory: String?,
    /** Docker `--cpus` value for a match container; null leaves it unlimited. */
    val matchCpus: String?,
    /** How long in-flight requests get to finish once shutdown starts. */
    val shutdownGraceSeconds: Long,
    /**
     * Directory the packet streams of replays are written to, or null to keep
     * them in memory.
     *
     * Not the database, whatever `YABRANKED_DATABASE_URL` says: a recording is
     * tens of megabytes per player, appended in chunks and read back in ranges. A
     * backend with no directory configured still records — it just loses the
     * recordings on restart, the same bargain the in-memory stores make.
     */
    val replayDir: String?,
    /**
     * S3-compatible object storage for replay packets — Cloudflare R2, MinIO, S3.
     *
     * Preferred over [replayDir] when set, because the hosts that make sense for
     * this service are the ones with no persistent disk. Endpoint may be null for
     * real AWS, where the region determines it.
     */
    val replayS3Endpoint: String?,
    val replayS3Bucket: String?,
    val replayS3AccessKey: String?,
    val replayS3SecretKey: String?,
    val replayS3Region: String,
    /**
     * How long an unpinned recording is kept, and how much one player may pin.
     *
     * These used to be compile-time constants, which on a host with four
     * gigabytes free meant the only way to fit the disk was a rebuild. A
     * recording is the largest thing this service writes; the numbers that bound
     * it belong wherever the rest of the deployment's numbers are.
     */
    val replayRetentionDays: Long?,
    val replaySavedPerPlayer: Int?,
    val replaySavedBytesPerPlayer: Long?,
    val replayMaxRecordingBytes: Long?,
    /**
     * Directory finished match containers' logs are copied into before the
     * container is destroyed, or null to keep discarding them.
     */
    val matchLogDir: String?,
    /**
     * `localPort=publicHost:publicPort` pairs, comma separated, for hosts behind
     * a tunnel rather than a port forward.
     *
     * Empty means ports are reachable under their own numbers, which is the
     * ordinary case and stays the default.
     */
    val portMap: Map<Int, String>,
) {
    val usesPostgres: Boolean get() = databaseUrl != null

    companion object {
        /**
         * Ceiling per match container, so one runaway server cannot take the
         * host down with it.
         *
         * Four cores, not two: Minecraft's world generation runs across worker
         * threads, and YAB pre-generates every side's spawn area before the game
         * can start. At two cores an observed match sat pinned at 100% for 83
         * seconds preloading 81 chunks, spent the whole time logging "Can't keep
         * up", and timed out YAB's async spawn search into its fallback
         * position. Raise it further on a host with cores to spare; the point of
         * the cap is only that one bad match server cannot take the host with it.
         */
        const val DEFAULT_MATCH_MEMORY = "4g"
        const val DEFAULT_MATCH_CPUS = "4"
        const val DEFAULT_SHUTDOWN_GRACE_SECONDS = 15L

        fun fromEnv(args: Array<String> = emptyArray(), env: Env = System::getenv): BackendConfig {
            val hostNetwork = env.bool("YABRANKED_HOST_NETWORK", default = true)
            val port = env.int("YABRANKED_PORT", default = 8080)
            if (port !in 1..65535) {
                throw ConfigException("YABRANKED_PORT must be 1..65535, got $port")
            }
            return BackendConfig(
                port = port,
                fakeAuth = "--fake-auth" in args || env.bool("YABRANKED_FAKE_AUTH", default = false),
                databaseUrl = env.string("YABRANKED_DATABASE_URL"),
                databaseUser = env.string("YABRANKED_DATABASE_USER"),
                databasePassword = env.string("YABRANKED_DATABASE_PASSWORD"),
                dbPoolSize = env.int("YABRANKED_DB_POOL_SIZE", default = Database.DEFAULT_POOL_SIZE)
                    .also {
                        if (it < 1) throw ConfigException("YABRANKED_DB_POOL_SIZE must be at least 1, got $it")
                    },
                dbConnectionTimeoutMs = env.positiveLong("YABRANKED_DB_CONNECTION_TIMEOUT_MS")
                    ?: Database.DEFAULT_CONNECTION_TIMEOUT_MS,
                season = env.string("YABRANKED_SEASON")?.let {
                    it.toIntOrNull()?.takeIf { season -> season >= 1 }
                        ?: throw ConfigException("YABRANKED_SEASON must be a positive integer, got '$it'")
                },
                seed = env.bool("YABRANKED_SEED", default = false),
                seedMe = env.string("YABRANKED_SEED_ME"),
                minClientVersion = env.string("YABRANKED_MIN_CLIENT_VERSION"),
                adminToken = env.string("YABRANKED_ADMIN_TOKEN"),
                orchestrate = env.bool("YABRANKED_ORCHESTRATE", default = false),
                matchImage = env.string("YABRANKED_MATCH_IMAGE") ?: "yabranked-match:latest",
                publicHost = env.string("YABRANKED_PUBLIC_HOST") ?: "localhost",
                // Without host networking the container has its own loopback, so
                // the agent has to go back out through the Docker host gateway.
                backendUrlForAgents = env.string("YABRANKED_BACKEND_URL_FOR_AGENTS")
                    ?: if (hostNetwork) "http://localhost:$port" else "http://host.docker.internal:$port",
                hostNetwork = hostNetwork,
                onlineMode = env.bool("YABRANKED_ONLINE_MODE", default = true),
                noShowTimeoutSeconds = env.positiveLong("YABRANKED_NO_SHOW_TIMEOUT_SECONDS"),
                postgameSeconds = env.positiveLong("YABRANKED_POSTGAME_SECONDS"),
                matchMemory = env.dockerSize("YABRANKED_MATCH_MEMORY", DEFAULT_MATCH_MEMORY),
                matchCpus = env.dockerCpus("YABRANKED_MATCH_CPUS", DEFAULT_MATCH_CPUS),
                shutdownGraceSeconds = env.positiveLong("YABRANKED_SHUTDOWN_GRACE_SECONDS")
                    ?: DEFAULT_SHUTDOWN_GRACE_SECONDS,
                replayDir = env.string("YABRANKED_REPLAY_DIR"),
                replayS3Endpoint = env.string("YABRANKED_REPLAY_S3_ENDPOINT"),
                replayS3Bucket = env.string("YABRANKED_REPLAY_S3_BUCKET"),
                replayS3AccessKey = env.string("YABRANKED_REPLAY_S3_ACCESS_KEY"),
                replayS3SecretKey = env.string("YABRANKED_REPLAY_S3_SECRET_KEY"),
                // R2 ignores the region but the SDK insists on one.
                replayS3Region = env.string("YABRANKED_REPLAY_S3_REGION") ?: "auto",
                replayRetentionDays = env.positiveLong("YABRANKED_REPLAY_RETENTION_DAYS"),
                replaySavedPerPlayer = env.positiveInt("YABRANKED_REPLAY_SAVED_PER_PLAYER"),
                replaySavedBytesPerPlayer = env.byteSize("YABRANKED_REPLAY_SAVED_BYTES_PER_PLAYER"),
                replayMaxRecordingBytes = env.byteSize("YABRANKED_REPLAY_MAX_RECORDING_BYTES"),
                matchLogDir = env.string("YABRANKED_MATCH_LOG_DIR"),
                portMap = parsePortMap(env.string("YABRANKED_PORT_MAP")),
            )
        }

        /**
         * `25600=abc.playit.gg:45123,25601=abc.playit.gg:45124`.
         *
         * A malformed entry is dropped rather than guessed at: mapping a port to
         * the wrong address sends players somewhere nobody is listening, which is
         * a worse outcome than the match failing to provision at all.
         */
        internal fun parsePortMap(raw: String?): Map<Int, String> =
            raw.orEmpty().split(',')
                .mapNotNull { entry ->
                    val local = entry.substringBefore('=', "").trim().toIntOrNull()
                    val target = entry.substringAfter('=', "").trim()
                    if (local == null || !isPlausibleAddress(target)) null else local to target
                }
                .toMap()

        /**
         * `host:port`, or a bare `host` when something else supplies the port.
         *
         * A bare hostname is not a mistake: a playit.gg "Minecraft Java" tunnel
         * publishes an SRV record, and the client is then meant to connect
         * without a port. Requiring a colon dropped those entries silently, which
         * is the worst way to be wrong about a config value.
         *
         * A colon still has to be followed by a number, so `host:` and
         * `host:abc` are rejected rather than passed on to a client that cannot
         * dial them.
         */
        private fun isPlausibleAddress(target: String): Boolean {
            if (target.isEmpty() || target.any { it.isWhitespace() }) return false
            if (!target.contains(':')) return true
            val port = target.substringAfterLast(':').toIntOrNull() ?: return false
            return port in 1..65535 && target.substringBeforeLast(':').isNotEmpty()
        }

        /** Blank is treated as unset: an empty env var is how compose spells "no value". */
        private fun Env.string(name: String): String? = this(name)?.trim()?.takeIf { it.isNotEmpty() }

        private val TRUE = setOf("1", "true", "yes", "on")
        private val FALSE = setOf("0", "false", "no", "off")

        private fun Env.bool(name: String, default: Boolean): Boolean {
            val raw = string(name) ?: return default
            return when (raw.lowercase()) {
                in TRUE -> true
                in FALSE -> false
                else -> throw ConfigException(
                    "$name must be one of ${(TRUE + FALSE).joinToString("/")}, got '$raw'"
                )
            }
        }

        private fun Env.int(name: String, default: Int): Int {
            val raw = string(name) ?: return default
            return raw.toIntOrNull() ?: throw ConfigException("$name must be an integer, got '$raw'")
        }

        private fun Env.positiveLong(name: String): Long? {
            val raw = string(name) ?: return null
            return raw.toLongOrNull()?.takeIf { it > 0 }
                ?: throw ConfigException("$name must be a positive number of seconds, got '$raw'")
        }

        private fun Env.positiveInt(name: String): Int? {
            val raw = string(name) ?: return null
            return raw.toIntOrNull()?.takeIf { it > 0 }
                ?: throw ConfigException("$name must be a positive integer, got '$raw'")
        }

        /**
         * A byte count, plain or with a k/m/g suffix — `2g` and `2147483648` are
         * the same limit. Reuses [SIZE] so a size means the same thing here as it
         * does for a Docker limit.
         */
        private fun Env.byteSize(name: String): Long? {
            val raw = string(name) ?: return null
            if (!SIZE.matches(raw)) {
                throw ConfigException("$name must look like 512m / 2g / 1073741824, got '$raw'")
            }
            val digits = raw.dropLastWhile { !it.isDigit() }.toLong()
            return when (raw.last().lowercaseChar()) {
                'k' -> digits * 1024
                'm' -> digits * 1024 * 1024
                'g' -> digits * 1024 * 1024 * 1024
                else -> digits
            }
        }

        /** Docker byte sizes: a number with an optional b/k/m/g suffix. */
        private val SIZE = Regex("^[0-9]+[bkmg]?$", RegexOption.IGNORE_CASE)

        private fun Env.dockerSize(name: String, default: String): String? {
            val raw = string(name) ?: return default
            // "unlimited" is spelled out so an operator can drop the limit
            // deliberately; an empty value keeps meaning "unset, use the default".
            if (raw.equals("unlimited", ignoreCase = true)) return null
            if (!SIZE.matches(raw)) {
                throw ConfigException("$name must look like 512m / 2g / 1073741824, got '$raw'")
            }
            return raw
        }

        private fun Env.dockerCpus(name: String, default: String): String? {
            val raw = string(name) ?: return default
            if (raw.equals("unlimited", ignoreCase = true)) return null
            val cpus = raw.toDoubleOrNull()
            if (cpus == null || cpus <= 0.0) {
                throw ConfigException("$name must be a positive number of cores, got '$raw'")
            }
            return raw
        }
    }
}
