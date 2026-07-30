plugins {
    id("codexmobile.provider-jvm-application")
}

dependencies {
    implementation(project(":documents"))
    implementation(project(":telegram"))
    implementation(libs.mcp.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pdfbox)
    implementation(libs.tess4j) {
        exclude(group = "org.apache.pdfbox", module = "pdfbox-tools")
    }
    implementation(libs.json)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "io.github.ciurlaro.codexmobile.providers.mcp.MainKt"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("provider.root", rootProject.projectDir.absolutePath)
}
