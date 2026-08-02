plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

// JVM 21 like :proto. `:agent` targets 25 because MC 26.2 requires it, and a
// 21-target library is consumable from 25 — the same arrangement :proto and
// :client already have.
kotlin {
    jvmToolchain(21)
}

dependencies {
    // must match kotlinx-serialization in YAB / fabric-language-kotlin, exactly
    // as :proto does — these classes end up in the same mod jar
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Provided at runtime by Minecraft itself; compiled against here so
    // ReplayStream can take the logger its caller already has.
    api("org.slf4j:slf4j-api:2.0.17")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
