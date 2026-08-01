package dev.yabranked.backend.orchestrator

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Resource ceiling for one match container. Without it a single runaway match
 * server takes the whole host — and with it every other match on that host —
 * down with it. Either value may be null to leave that dimension unlimited.
 */
data class ContainerLimits(
    /** Docker `--memory`, e.g. "4g". */
    val memory: String? = null,
    /** Docker `--cpus`, e.g. "2". */
    val cpus: String? = null,
)

/**
 * Minimal container runtime abstraction so the orchestrator can be tested
 * with a fake. The real implementation shells out to the docker CLI.
 */
interface ContainerRuntime {
    /**
     * Starts a container, returns its id. Throws on failure.
     * [publishPorts] maps host port -> container port (ignored with [hostNetwork]).
     *
     * [secretEnv] reaches the container the same way as [env] but must never
     * appear on the runtime's command line: anything passed as an argument is
     * readable by every process on the host for as long as the command runs.
     */
    fun run(
        name: String,
        image: String,
        env: Map<String, String>,
        hostNetwork: Boolean,
        publishPorts: Map<Int, Int> = emptyMap(),
        secretEnv: Map<String, String> = emptyMap(),
        limits: ContainerLimits = ContainerLimits(),
    ): String

    /** Force-removes a container; never throws. */
    fun remove(name: String)

    /**
     * The container's console output, newest [tailLines] lines, or null when it
     * cannot be read.
     *
     * Exists because [remove] destroys the only copy. A match server's log is
     * the sole evidence for anything that goes wrong *inside* a match — whether
     * the agent configured YAB, whether the game ever ended, why a result was
     * never reported — and every one of those investigations used to begin after
     * the container had already been deleted.
     *
     * Defaulted so a test fake needs no opinion about logging.
     */
    fun logs(name: String, tailLines: Int): String? = null

    /**
     * Names of existing containers (running or not) whose name starts with
     * [namePrefix]. Used to sweep up match servers orphaned by a restart.
     */
    fun list(namePrefix: String): List<String>

    /**
     * Whether [name] is a container that is still running.
     *
     * False for a container that exited, and false for one that does not exist
     * at all — the caller only ever asks about containers it started, so the two
     * mean the same thing to it. Never throws: a runtime that cannot be reached
     * answers `true`, because "docker is briefly unavailable" must not be read
     * as "every live match has died".
     */
    fun isRunning(name: String): Boolean
}

class DockerCliRuntime : ContainerRuntime {
    private val log = LoggerFactory.getLogger("docker")

    private fun exec(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .start()
        // Drain on another thread: reading to EOF on this one before waitFor
        // means a chatty command that fills the pipe buffer would hang forever
        // and the timeout below would never be reached.
        val output = StringBuilder()
        val drain = Thread {
            runCatching { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }
        }.apply { isDaemon = true; start() }

        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            drain.join(1000)
            error("docker command timed out: ${args.joinToString(" ")}")
        }
        drain.join(5000)
        return process.exitValue() to output.toString().trim()
    }

    override fun run(
        name: String,
        image: String,
        env: Map<String, String>,
        hostNetwork: Boolean,
        publishPorts: Map<Int, Int>,
        secretEnv: Map<String, String>,
        limits: ContainerLimits,
    ): String {
        // Secrets go in via --env-file, never as -e on the argv: `ps` shows the
        // full command line of `docker run` to any user on the host. The file is
        // created 0600 by the JDK on POSIX and deleted as soon as docker exits.
        val secretFile = if (secretEnv.isEmpty()) null else writeEnvFile(secretEnv)
        try {
            val args = buildList {
                add("docker"); add("run"); add("-d")
                add("--name"); add(name)
                limits.memory?.let { add("--memory"); add(it) }
                limits.cpus?.let { add("--cpus"); add(it) }
                if (hostNetwork) {
                    add("--network"); add("host")
                } else {
                    for ((host, container) in publishPorts) {
                        add("-p"); add("$host:$container")
                    }
                    // lets the agent reach the backend on the host from inside the container
                    add("--add-host"); add("host.docker.internal:host-gateway")
                }
                for ((key, value) in env) {
                    add("-e"); add("$key=$value")
                }
                secretFile?.let { add("--env-file"); add(it.toString()) }
                add(image)
            }
            val (exit, output) = exec(*args.toTypedArray())
            if (exit != 0) error("docker run failed ($exit): $output")
            log.info("started container {} ({})", name, output.take(12))
            return output
        } finally {
            secretFile?.let { Files.deleteIfExists(it) }
        }
    }

    /**
     * Docker parses an env file as KEY=VALUE per line, so a value with a
     * newline in it would inject another variable — reject rather than truncate.
     */
    private fun writeEnvFile(env: Map<String, String>): Path {
        require(env.values.none { '\n' in it || '\r' in it }) { "secret env values must be single-line" }
        val file = Files.createTempFile("yabranked-env-", ".list")
        Files.writeString(file, env.entries.joinToString("\n") { "${it.key}=${it.value}" })
        return file
    }

    override fun remove(name: String) {
        val (exit, output) = exec("docker", "rm", "-f", name)
        if (exit != 0) log.warn("docker rm {} failed ({}): {}", name, exit, output)
    }

    override fun logs(name: String, tailLines: Int): String? {
        val (exit, output) = runCatching {
            exec("docker", "logs", "--timestamps", "--tail", tailLines.toString(), name)
        }.getOrElse {
            log.warn("docker logs {} failed: {}", name, it.toString())
            return null
        }
        // A container that is already gone is not an error worth a stack trace:
        // the sweep reaches containers docker has removed on its own.
        if (exit != 0) {
            log.debug("docker logs {} exited {}: {}", name, exit, output)
            return null
        }
        return output.takeIf { it.isNotBlank() }
    }

    override fun isRunning(name: String): Boolean {
        val (exit, output) = runCatching {
            exec("docker", "inspect", "-f", "{{.State.Running}}", name)
        }.getOrElse {
            log.warn("docker inspect {} failed", name, it)
            // See the interface doc: an unreachable runtime is not evidence of a
            // dead match, and answering false here would void every live one.
            return true
        }
        // A non-zero exit is "no such container", which for a container we
        // started is the same answer as "it exited and was removed".
        if (exit != 0) return false
        return output.trim().equals("true", ignoreCase = true)
    }

    override fun list(namePrefix: String): List<String> {
        val (exit, output) = exec(
            "docker", "ps", "-a", "--filter", "name=^/$namePrefix", "--format", "{{.Names}}",
        )
        if (exit != 0) {
            log.warn("docker ps failed ({}): {}", exit, output)
            return emptyList()
        }
        return output.lines().map(String::trim).filter { it.startsWith(namePrefix) }
    }

    private companion object {
        const val COMMAND_TIMEOUT_SECONDS = 60L
    }
}
