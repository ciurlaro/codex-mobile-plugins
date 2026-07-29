pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "codex-mobile-plugins"
providers.gradleProperty("codexMobile.extensionProviderApiBuild").orNull?.let { apiBuild ->
    includeBuild(apiBuild) {
        dependencySubstitution {
            substitute(module("io.github.ciurlaro.codexmobile:extension-provider-api"))
                .using(project(":"))
        }
    }
}
include(":documents")
include(":telegram")
include(":mcp-server")
if (file("android").isDirectory) {
    include(":documents-android")
    project(":documents-android").projectDir = file("android/documents")
    include(":telegram-android")
    project(":telegram-android").projectDir = file("android/telegram")
}
