import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    `maven-publish`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

group = "io.github.ciurlaro.codexmobile.providers"
version = libs.findVersion("provider").get().requiredVersion

extensions.configure<LibraryExtension> {
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") { from(components["release"]) }
        }
    }
}

dependencies {
    "api"(libs.findLibrary("extension-provider-api").get())
    "implementation"(libs.findLibrary("kotlinx-coroutines-core").get())
    "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
    "androidTestImplementation"(libs.findBundle("android-test").get())
}

dependencyLocking { lockAllConfigurations() }
