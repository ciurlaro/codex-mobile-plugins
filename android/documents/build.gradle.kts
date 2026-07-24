plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "io.github.ciurlaro.codexmobile.providers.documents"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    sourceSets["main"].kotlin.directories.add(
        project.file("../../shared/src/commonMain/kotlin/io/github/ciurlaro/codexmobile/providers/documents").path,
    )
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":app:android"))
    implementation(project(":agent:codex"))
    implementation(project(":platform:android"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.legere:pdfiumandroid-core:2.0.0") {
        // PdfPageCacheBase is not used; keep Guava out of both the split and base APK.
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation("com.google.mlkit:text-recognition:16.0.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
