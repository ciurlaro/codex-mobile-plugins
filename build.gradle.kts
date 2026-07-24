plugins {
    kotlin("multiplatform") version "2.3.10" apply false
    kotlin("jvm") version "2.3.10" apply false
}

allprojects {
    dependencyLocking { lockAllConfigurations() }
}
