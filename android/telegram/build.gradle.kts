import java.util.Properties

plugins {
    id("com.android.dynamic-feature")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf(File::isFile)?.inputStream()?.use { load(it) }
}
val telegramApiId = providers.gradleProperty("codexMobile.telegram.apiId")
    .orElse(providers.environmentVariable("CODEX_MOBILE_TELEGRAM_API_ID"))
    .orElse(localProperties.getProperty("codexMobile.telegram.apiId", ""))
    .orElse("")
val telegramApiHash = providers.gradleProperty("codexMobile.telegram.apiHash")
    .orElse(providers.environmentVariable("CODEX_MOBILE_TELEGRAM_API_HASH"))
    .orElse(localProperties.getProperty("codexMobile.telegram.apiHash", ""))
    .orElse("")
val telegramNdk = providers.gradleProperty("codexMobile.androidNdkPath")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
    .orElse(providers.provider { "${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")}/ndk/29.0.14206865" })

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "io.github.ciurlaro.codexmobile.providers.telegram"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "TELEGRAM_API_ID", telegramApiId.get().asBuildConfigString())
        buildConfigField("String", "TELEGRAM_API_HASH", telegramApiHash.get().asBuildConfigString())
        buildConfigField("String", "TDLIB_VERSION", "1.8.66".asBuildConfigString())
        buildConfigField("String", "TDLIB_COMMIT", "022d60202e446ad1287b9fb68e687c8a0760788b".asBuildConfigString())
    }
    buildFeatures { buildConfig = true }
    sourceSets["main"].kotlin.directories.add(
        project.file("../../shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/telegram").path,
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val telegramReleaseCredentialsValid = telegramApiId.zip(telegramApiHash) { apiId, apiHash ->
    apiId.toIntOrNull()?.let { it > 0 } == true && apiHash.matches(Regex("[0-9a-fA-F]{32}"))
}
val verifyTelegramReleaseCredentials = tasks.register("verifyTelegramReleaseCredentials") {
    inputs.property("configured", telegramReleaseCredentialsValid)
    doLast {
        check(inputs.properties["configured"] == true) {
            "Release Telegram providers require CODEX_MOBILE_TELEGRAM_API_ID and CODEX_MOBILE_TELEGRAM_API_HASH"
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyTelegramReleaseCredentials)
}

val telegramLibrary = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libtdjsonjava.so")
val prepareTelegramScript = layout.projectDirectory.file("../../scripts/prepare-telegram-library.sh")
val prepareTelegramLibrary = tasks.register<Exec>("prepareTelegramLibrary") {
    inputs.property("tdlibVersion", "1.8.66")
    inputs.property("tdlibCommit", "022d60202e446ad1287b9fb68e687c8a0760788b")
    inputs.property("opensslVersion", "3.5.7")
    inputs.file(prepareTelegramScript)
    outputs.file(telegramLibrary)
    onlyIf("TDLib output is absent") { !outputs.files.singleFile.isFile }
    commandLine(prepareTelegramScript.asFile, telegramNdk.get(), telegramLibrary.asFile.absolutePath)
}

tasks.named("preBuild").configure { dependsOn(prepareTelegramLibrary) }

dependencies {
    implementation(project(":app:android"))
    implementation(project(":agent:codex"))
    implementation(project(":platform:android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
