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
) {
    val usesPostgres: Boolean get() = databaseUrl != null

    companion object {
        /** Two cores' worth of a bingo server is plenty; a runaway one gets no more. */
        const val DEFAULT_MATCH_MEMORY = "4g"
        const val DEFAULT_MATCH_CPUS = "2"
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
            )
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
