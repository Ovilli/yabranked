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

    runs {
        named("client") {
            programArgs("--username", "AliceDev")
        }
        // second instance for local 1v1 testing: own run dir + username
        create("client2") {
            client()
            runDir("run2")
            programArgs("--username", "BobDev")
        }
    }
}

// Proto classes are flattened into the mod jar (below) rather than nested as a
// jar-in-jar: Fabric's JiJ only loads nested jars that are themselves mods, and
// proto is a plain library. Flattening keeps its classes on the runtime
// classpath without giving proto a fabric.mod.json.
val bundledProto: Configuration by configurations.creating {
    isTransitive = false // only proto's own classes; kotlinx-serialization comes from FLK
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    // Minecraft 26.x ships unobfuscated — no mappings dependency needed
    minecraft("com.mojang:minecraft:26.2")

    implementation("net.fabricmc:fabric-loader:0.19.3")
    implementation("net.fabricmc.fabric-api:fabric-api:0.152.1+26.2")
    // provides kotlin stdlib + kotlinx-serialization at runtime
    implementation("net.fabricmc:fabric-language-kotlin:1.13.11+kotlin.2.3.21")

    // shared wire model — compile against it and bundle its classes (see above)
    implementation(project(":proto"))
    bundledProto(project(":proto"))
}

tasks.named<Jar>("jar") {
    from(bundledProto.elements.map { jars -> jars.map { zipTree(it) } })
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}
