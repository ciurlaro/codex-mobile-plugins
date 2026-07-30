plugins {
    id("codexmobile.provider-kmp-library")
}

kotlin {
    sourceSets {
        jvmMain.dependencies { compileOnly(libs.json) }
    }
}
