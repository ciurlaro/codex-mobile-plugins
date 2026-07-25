plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":documents"))
    implementation(project(":telegram"))
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    implementation("net.sourceforge.tess4j:tess4j:5.19.0") {
        exclude(group = "org.apache.pdfbox", module = "pdfbox-tools")
    }
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.ciurlaro.codexmobile.providers.mcp.MainKt"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("provider.root", rootProject.projectDir.absolutePath)
}
