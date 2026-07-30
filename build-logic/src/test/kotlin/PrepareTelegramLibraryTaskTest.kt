import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class PrepareTelegramLibraryTaskTest {
    @Test
    fun `task is up to date and reuses configuration cache`() {
        val project = createTempDirectory("telegram-task").toFile()
        try {
            project.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            project.resolve("ndk").mkdir()
            project.resolve("scripts").mkdir()
            project.resolve("scripts/prepare-telegram-library.sh").apply {
                writeText(
                    """
                    #!/usr/bin/env bash
                    set -euo pipefail
                    mkdir -p "${'$'}(dirname "${'$'}2")"
                    printf '%s' "${'$'}1" > "${'$'}2"
                    """.trimIndent(),
                )
                assertTrue(setExecutable(true))
            }
            project.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("codexmobile.telegram-library")
                }
                """.trimIndent(),
            )

            val first = run(project)
            assertEquals(TaskOutcome.SUCCESS, first.task(":prepareTelegramLibrary")?.outcome)
            val second = run(project)
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":prepareTelegramLibrary")?.outcome)
            assertTrue(second.output.contains("Reusing configuration cache."))
        } finally {
            project.deleteRecursively()
        }
    }

    private fun run(project: File) = GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(
            "prepareTelegramLibrary",
            "-PcodexMobile.androidNdkPath=${project.resolve("ndk").absolutePath}",
            "--configuration-cache",
        )
        .build()
}
