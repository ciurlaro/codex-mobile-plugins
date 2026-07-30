import org.gradle.api.tasks.testing.Test

plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
dependencyLocking { lockAllConfigurations() }
