plugins {
    id("com.android.library")
    `maven-publish`
}

group = "io.github.ciurlaro.codexmobile.providers"
version = "1.0.0"

android {
    namespace = "io.github.ciurlaro.codexmobile.providers.documents"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") { from(components["release"]) }
        }
    }
}

dependencies {
    api(project(":documents"))
    api("io.github.ciurlaro.codexmobile:provider-api:2.0.0")
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
