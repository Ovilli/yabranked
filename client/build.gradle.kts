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
    // Mod Menu, for the config-screen entrypoint only — compile-only, see below.
    maven("https://maven.terraformersmc.com/releases") { name = "Terraformers" }
}

// Where `runClient` points. Overridable from the command line for pointing a dev
// client at a staging backend: -PyabrankedLocalBackend=https://...
val localBackend: String = (findProperty("yabrankedLocalBackend") as String?) ?: "http://localhost:8080"

loom {
    // pure client-side mod: compile against the client distribution
    clientOnlyMinecraftJar()

    runs {
        // Both dev clients point at a local backend explicitly.
        //
        // The mod's built-in default is the *published* service, because a
        // shipped jar has to work with no configuration at all. That makes the
        // override the development concern rather than the deployment one — and
        // without these lines `runClient` would quietly queue against
        // production, which is a bad way to find out.
        named("client") {
            programArgs("--username", "AliceDev")
            vmArg("-Dyabranked.url=$localBackend")
        }
        // second instance for local 1v1 testing: own run dir + username
        create("client2") {
            client()
            runDir("run2")
            programArgs("--username", "BobDev")
            vmArg("-Dyabranked.url=$localBackend")
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

    // Mod Menu is an optional integration and must stay compile-only: the mod
    // has to load with it absent. Nothing references ModMenuIntegration except
    // the "modmenu" entrypoint, which only Mod Menu itself ever reads, so the
    // class is never loaded when Mod Menu is not installed. Not `modCompileOnly`
    // — Minecraft 26.x is unobfuscated, so there is nothing to remap.
    compileOnly("com.terraformersmc:modmenu:20.0.1")

    testImplementation(kotlin("test"))
}

// Plain JVM unit tests for the mod's non-rendering logic — no game harness.
// Loom already puts the Minecraft jar on the test classpath, so classes that
// merely *mention* net.minecraft types (Ui, QueueBadge) load fine; anything
// that actually constructs a Screen or calls Minecraft.getInstance() does not,
// and belongs behind an extracted, MC-free helper instead.
tasks.test {
    useJUnitPlatform()
    // Minecraft's log4j config rides in on that classpath and opens
    // logs/latest.log relative to the working directory — which is the module
    // root, so running tests would litter client/logs/. Keep it under build/.
    workingDir = layout.buildDirectory.dir("test-work").get().asFile
    doFirst { workingDir.mkdirs() }
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
