import java.io.File

val telegramNdkVersion = "29.0.14206865"
val telegramNdk = providers.gradleProperty("codexMobile.androidNdkPath")
    .orElse(providers.environmentVariable("ANDROID_NDK_HOME"))
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
    .orElse(providers.provider {
        "${System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")}/ndk/$telegramNdkVersion"
    })

val prepareTelegramLibrary = tasks.register<PrepareTelegramLibraryTask>("prepareTelegramLibrary") {
    tdlibVersion.set("1.8.66")
    tdlibCommit.set("022d60202e446ad1287b9fb68e687c8a0760788b")
    opensslVersion.set("3.5.7")
    ndkVersion.set(telegramNdkVersion)
    ndkDirectory.set(layout.dir(telegramNdk.map(::File)))
    preparationScript.set(rootProject.layout.projectDirectory.file("scripts/prepare-telegram-library.sh"))
    outputLibrary.set(
        layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libtdjsonjava.so"),
    )
}

pluginManager.withPlugin("com.android.library") {
    tasks.named("preBuild").configure { dependsOn(prepareTelegramLibrary) }
}
