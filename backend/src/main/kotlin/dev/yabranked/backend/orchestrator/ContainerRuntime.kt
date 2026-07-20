package dev.yabranked.backend.orchestrator

import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * Minimal container runtime abstraction so the orchestrator can be tested
 * with a fake. The real implementation shells out to the docker CLI.
 */
interface ContainerRuntime {
    /**
     * Starts a container, returns its id. Throws on failure.
     * [publishPorts] maps host port -> container port (ignored with [hostNetwork]).
     */
    fun run(
        name: String,
        image: String,
        env: Map<String, String>,
        hostNetwork: Boolean,
        publishPorts: Map<Int, Int> = emptyMap(),
    ): String

    /** Force-removes a container; never throws. */
    fun remove(name: String)
}

class DockerCliRuntime : ContainerRuntime {
    private val log = LoggerFactory.getLogger("docker")

    private fun exec(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(*args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("docker command timed out: ${args.joinToString(" ")}")
        }
        return process.exitValue() to output
    }

    override fun run(
        name: String,
        image: String,
        env: Map<String, String>,
        hostNetwork: Boolean,
        publishPorts: Map<Int, Int>,
    ): String {
        val args = buildList {
            add("docker"); add("run"); add("-d")
            add("--name"); add(name)
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
            add(image)
        }
        val (exit, output) = exec(*args.toTypedArray())
        if (exit != 0) error("docker run failed ($exit): $output")
        log.info("started container {} ({})", name, output.take(12))
        return output
    }

    override fun remove(name: String) {
        val (exit, output) = exec("docker", "rm", "-f", name)
        if (exit != 0) log.warn("docker rm {} failed ({}): {}", name, exit, output)
    }
}
