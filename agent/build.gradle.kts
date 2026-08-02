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

// agent-core's classes are flattened into the mod jar rather than nested as a
// jar-in-jar, for the same reason :client does it to :proto: Fabric's JiJ only
// loads nested jars that are themselves mods, and agent-core is a plain
// library. Without this the mod loads and then dies on NoClassDefFoundError at
// the first AgentConfig.fromEnv — which, since a config failure is how the
// agent stays inert, would look exactly like a correctly inert agent.
val bundledCore: Configuration by configurations.creating {
    isTransitive = false // only agent-core's own classes; kotlinx + slf4j come from the game
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Minecraft 26.x ships unobfuscated — no mappings dependency needed
    minecraft("com.mojang:minecraft:26.2")

    // the Minecraft-free half: env parsing, void deadlines, replay stream format
    implementation(project(":agent-core"))
    bundledCore(project(":agent-core"))

    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.156.0+26.2")
    // provides kotlin stdlib + kotlinx-serialization at runtime
    implementation("net.fabricmc:fabric-language-kotlin:1.13.11+kotlin.2.3.21")

    // provided at runtime by the YAB mod jar (which nests the api jar)
    compileOnly("me.jfenn.bingo:api:2.12.1")

}

// What is left in this module is the Minecraft coupling itself — the Netty tap,
// the mixin accessors, the YAB driving — and there is no useful way to unit-test
// it without a server. Everything that *was* testable now lives in :agent-core,
// which CI runs. This module keeps no test source set on purpose: one here would
// be a suite that never runs anywhere, since :agent is not in the build at all
// without me.jfenn.bingo:api in mavenLocal.

tasks.named<Jar>("jar") {
    from(bundledCore.elements.map { jars -> jars.map { zipTree(it) } })
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
