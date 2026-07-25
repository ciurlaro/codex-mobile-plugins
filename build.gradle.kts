plugins {
    id("com.android.library") version "9.2.1" apply false
    kotlin("multiplatform") version "2.3.10" apply false
    kotlin("jvm") version "2.3.10" apply false
}

allprojects {
    dependencyLocking { lockAllConfigurations() }
}

tasks.register("test") {
    group = "verification"
    description = "Runs capability contract tests and MCP server tests."
    dependsOn(":documents:jvmTest", ":telegram:jvmTest", ":mcp-server:test")
}
