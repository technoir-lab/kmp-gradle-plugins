package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeRunner
import org.gradle.api.DefaultTask
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
internal abstract class CMakeBuildTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectDirectory: DirectoryProperty

    @get:Input
    abstract val targetName: Property<String>

    @get:Input
    abstract val buildType: Property<String>

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
    abstract val buildOutputDirectory: DirectoryProperty

    @TaskAction
    fun build() {
        val targetName = targetName.get()
        val buildType = buildType.get()
        val configureDirectory = configureDirectory.get().asFile.toPath()

        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.build(configureDirectory, targetName, buildType)
    }
}
