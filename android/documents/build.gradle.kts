plugins {
    id("codexmobile.provider-android-library")
}

android {
    namespace = "io.github.ciurlaro.codexmobile.providers.documents"
}

dependencies {
    api(project(":documents"))
    implementation(libs.pdfium) {
        // PdfPageCacheBase is not used; keep Guava out of both the split and base APK.
        exclude(group = "com.google.guava", module = "guava")
    }
    implementation(libs.mlkit.text.recognition)
}
