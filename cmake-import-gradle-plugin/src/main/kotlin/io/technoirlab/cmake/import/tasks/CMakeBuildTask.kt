package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeRunner
import io.technoirlab.cmake.import.internal.CMakeTaskStateManager
import io.technoirlab.gradle.asPath
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject
import kotlin.io.path.deleteIfExists

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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    internal abstract val generateStateFile: RegularFileProperty

    /**
     * CMake's non-relocatable build tree.
     */
    @get:LocalState
    abstract val configureDirectory: DirectoryProperty

    @get:Input
    internal abstract val configureDirectoryPath: Property<String>

    /**
     * Records successful execution without overlapping CMake's build tree.
     */
    @get:OutputFile
    internal abstract val buildStateFile: RegularFileProperty

    @TaskAction
    internal fun build() {
        val target = cmakeTarget.get()
        val buildType = cmakeBuildType.get()
        val configureDirectory = configureDirectory.get().asPath()
        val stateFile = buildStateFile.get().asPath()
        val stateManager = CMakeTaskStateManager()

        stateFile.deleteIfExists()
        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.build(configureDirectory, target, buildType)
        stateManager.write(stateFile, stateManager.create())
    }
}
