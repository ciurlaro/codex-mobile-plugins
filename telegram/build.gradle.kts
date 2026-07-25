plugins {
    kotlin("multiplatform")
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile.providers"
version = "1.0.0"

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        jvmMain.dependencies { compileOnly("org.json:json:20250517") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
