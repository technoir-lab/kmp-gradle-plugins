package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeRunner
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
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
    abstract val cmakeTarget: Property<String>

    @get:Input
    abstract val cmakeBuildType: Property<String>

    @get:Input
    @get:Optional
    abstract val cmakeComponent: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    /**
     * CMake's non-relocatable build tree.
     */
    @get:LocalState
    abstract val configureDirectory: DirectoryProperty

    /**
     * Staged `include/` and `lib/` directories installed by CMake.
     */
    @get:OutputDirectory
    abstract val installDirectory: DirectoryProperty

    @TaskAction
    internal fun install() {
        val buildType = cmakeBuildType.get()
        val component = cmakeComponent.orNull
        val configureDirectory = configureDirectory.get().asFile.toPath()
        val installDirectory = installDirectory.get().asFile.toPath()

        fileSystemOperations.delete {
            delete(installDirectory)
        }
        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.install(configureDirectory, installDirectory, buildType, component)
    }
}
