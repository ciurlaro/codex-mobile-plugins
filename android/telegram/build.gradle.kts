plugins {
    id("codexmobile.provider-android-library")
    id("codexmobile.telegram-library")
}

fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "io.github.ciurlaro.codexmobile.providers.telegram"
    defaultConfig {
        buildConfigField("String", "TDLIB_VERSION", "1.8.66".asBuildConfigString())
        buildConfigField("String", "TDLIB_COMMIT", "022d60202e446ad1287b9fb68e687c8a0760788b".asBuildConfigString())
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    api(project(":telegram"))
}
