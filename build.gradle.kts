plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
}

subprojects {
    group = "dev.yabranked"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}
