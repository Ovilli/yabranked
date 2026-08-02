plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    // the no-remap loom variant (same id YAB uses) — required for unobfuscated MC 26.x
    id("net.fabricmc.fabric-loom") version "1.16-SNAPSHOT"
}

kotlin {
    // Minecraft 26.2 requires Java 25
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    // local YAB api build (me.jfenn.bingo:api) — run :api:publishToMavenLocal in the YAB repo
    mavenLocal()
}

dependencies {
    // Minecraft 26.x ships unobfuscated — no mappings dependency needed
    minecraft("com.mojang:minecraft:26.2")

    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.156.0+26.2")
    // provides kotlin stdlib + kotlinx-serialization at runtime
    implementation("net.fabricmc:fabric-language-kotlin:1.13.11+kotlin.2.3.21")

    // provided at runtime by the YAB mod jar (which nests the api jar)
    compileOnly("me.jfenn.bingo:api:2.12.1")

    testImplementation(kotlin("test"))
}

// Unit tests for the agent's non-Minecraft logic — the env parsing, the void
// deadlines and the replay stream's on-disk format. Everything under test here
// is deliberately reachable without a server: the agent's failures are
// expensive to reproduce (they need a container, a match and two clients) and
// cheap to assert.
//
// These do NOT run in CI. `:agent` is only in the build when
// `me.jfenn.bingo:api` is in mavenLocal, and that artifact is published to a
// GitLab package registry behind a job token, so a CI runner cannot fetch it.
// Run them locally with `./gradlew :agent:test`.
tasks.test {
    useJUnitPlatform()
    // Minecraft's log4j config rides in on Loom's test classpath and opens
    // logs/latest.log relative to the working directory; keep that under build/
    // rather than in the module root, as :client does.
    workingDir = layout.buildDirectory.dir("test-work").get().asFile
    doFirst { workingDir.mkdirs() }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
