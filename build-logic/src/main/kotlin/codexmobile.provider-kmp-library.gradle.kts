import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    kotlin("multiplatform")
    `maven-publish`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "io.github.ciurlaro.codexmobile.providers"
version = libs.findVersion("provider").get().requiredVersion

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.findLibrary("kotlinx-coroutines-core").get())
            implementation(libs.findLibrary("kotlinx-serialization-json").get())
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

dependencyLocking { lockAllConfigurations() }
