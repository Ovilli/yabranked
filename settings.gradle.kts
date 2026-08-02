pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "yabranked"

include("proto")
include("backend")
include("client")

// The agent's Minecraft-free half: env parsing, the void deadlines and the
// replay stream format. Split out of `:agent` precisely so it is *always* in
// the build — it has no Loom and no YAB dependency, so CI can compile and test
// it even though it can never build `:agent` itself (see below).
include("agent-core")

/*
 * `agent` is included only when the artifact it cannot be configured without is
 * actually present.
 *
 * It compiles against `me.jfenn.bingo:api` from **mavenLocal**, published by a
 * checkout of the YAB repository. Loom resolves that dependency at
 * *configuration* time, and Gradle configures every project in a build whatever
 * task was asked for — so an absent artifact does not fail `:agent:build`, it
 * fails `./gradlew :backend:test`, `./gradlew projects`, and everything else,
 * with an error naming a module the caller never mentioned. Anyone without a
 * YAB checkout could not run the test suite at all, which is exactly what CI
 * discovered by being the first such machine.
 *
 * The trade is that the set of modules depends on the environment, so
 * `./gradlew projects` is not the same everywhere. That is worth saying out
 * loud, which is what the message below does. Override with `-PskipAgent=true`
 * to reproduce the trimmed build on a machine that does have the artifact.
 */
val agentApiPresent = File(System.getProperty("user.home"), ".m2/repository/me/jfenn/bingo/api").isDirectory
val skipAgent = (settings.providers.gradleProperty("skipAgent").orNull ?: "false").toBoolean()

if (agentApiPresent && !skipAgent) {
    include("agent")
} else {
    val why = if (skipAgent) "-PskipAgent=true was passed" else "me.jfenn.bingo:api is not in mavenLocal"
    logger.lifecycle(
        "yabranked: skipping the :agent module — $why. " +
            "Publish it from the YAB repo (./gradlew :api:publishToMavenLocal) to build the match-server mod."
    )
}
