plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

kotlin {
    jvmToolchain(21)
}

val ktorVersion = "3.3.0"

dependencies {
    implementation(project(":proto"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-server-websockets:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:1.5.18")

    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.7")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
}

application {
    mainClass.set("dev.yabranked.backend.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runMock") {
    group = "application"
    description = "Run the mock client against a local backend (start it with --fake-auth first)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.yabranked.backend.mock.MockClientKt")
}
