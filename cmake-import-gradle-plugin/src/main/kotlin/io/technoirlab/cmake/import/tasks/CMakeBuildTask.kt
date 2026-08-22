package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeRunner
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Builds a target from a generated CMake build system.
 */
@DisableCachingByDefault(because = "CMake manages non-relocatable build state")
abstract class CMakeBuildTask @Inject internal constructor(
    private val execOperations: ExecOperations,
) : BaseCMakeTask() {

    @get:Input
    abstract val cmakeTarget: Property<String>

    @get:Input
    abstract val cmakeBuildType: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val generateOutputDirectory: DirectoryProperty

    /**
     * CMake's non-relocatable build tree.
     */
    @get:LocalState
    abstract val configureDirectory: DirectoryProperty

    /**
     * Empty directory recording successful completion without overlapping other task outputs.
     */
    @get:OutputDirectory
    internal abstract val buildOutputDirectory: DirectoryProperty

    @TaskAction
    internal fun build() {
        val target = cmakeTarget.get()
        val buildType = cmakeBuildType.get()
        val configureDirectory = configureDirectory.get().asFile.toPath()

        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.build(configureDirectory, target, buildType)
    }
}
