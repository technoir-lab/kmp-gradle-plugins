package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeRunner
import io.technoirlab.gradle.asPath
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Installs a built CMake target.
 */
@DisableCachingByDefault(because = "CMake manages non-relocatable build state")
abstract class CMakeInstallTask @Inject internal constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : BaseCMakeTask() {
    @get:Input
    abstract val cmakeBuildType: Property<String>

    @get:Input
    @get:Optional
    abstract val cmakeComponent: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    internal abstract val buildStateFile: RegularFileProperty

    /**
     * CMake's non-relocatable build tree.
     */
    @get:LocalState
    abstract val configureDirectory: DirectoryProperty

    @get:Input
    internal abstract val configureDirectoryPath: Property<String>

    /**
     * Staged `include/` and `lib/` directories installed by CMake.
     */
    @get:OutputDirectory
    abstract val installDirectory: DirectoryProperty

    @TaskAction
    internal fun install() {
        val buildType = cmakeBuildType.get()
        val component = cmakeComponent.orNull
        val configureDirectory = configureDirectory.get().asPath()
        val installDirectory = installDirectory.get().asPath()

        fileSystemOperations.delete {
            delete(installDirectory)
        }
        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.install(configureDirectory, installDirectory, buildType, component)
    }
}
