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
}

loom {
    // pure client-side mod: compile against the client distribution
    clientOnlyMinecraftJar()
}

dependencies {
    // Minecraft 26.x ships unobfuscated — no mappings dependency needed
    minecraft("com.mojang:minecraft:26.2")

    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.152.1+26.2")
    // provides kotlin stdlib + kotlinx-serialization at runtime
    implementation("net.fabricmc:fabric-language-kotlin:1.13.11+kotlin.2.3.21")
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
