import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class PrepareTelegramLibraryTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val tdlibVersion: Property<String>

    @get:Input
    abstract val tdlibCommit: Property<String>

    @get:Input
    abstract val opensslVersion: Property<String>

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val preparationScript: RegularFileProperty

    @get:OutputFile
    abstract val outputLibrary: RegularFileProperty

    @TaskAction
    fun prepare() {
        execOperations.exec {
            executable(preparationScript.get().asFile)
            args(ndkDirectory.get().asFile.absolutePath, outputLibrary.get().asFile.absolutePath)
        }
    }
}
