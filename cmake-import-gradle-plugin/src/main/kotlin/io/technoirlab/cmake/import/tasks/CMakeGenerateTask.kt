package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.CMakeImportPlugin.Companion.PROBLEM_GROUP
import io.technoirlab.cmake.import.internal.CMakeRunner
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Generates a CMake build system for a project.
 */
@Suppress("UnstableApiUsage")
@DisableCachingByDefault(because = "CMake manages non-relocatable build state")
internal abstract class CMakeGenerateTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val problems: Problems,
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectDirectory: DirectoryProperty

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    abstract val defines: MapProperty<String, String>

    /**
     * CMake's non-relocatable build tree.
     */
    @get:LocalState
    abstract val configureDirectory: DirectoryProperty

    @get:OutputFile
    abstract val cacheFile: RegularFileProperty

    /**
     * Empty directory recording successful completion without overlapping CMake's build tree.
     */
    @get:OutputDirectory
    abstract val generateOutputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val projectDir = projectDirectory.get().asFile.toPath()
        val buildType = buildType.get()
        val configureDirectory = configureDirectory.get().asFile.toPath()

        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.generate(projectDir, configureDirectory, buildType, defines.get())

        val cacheFile = cacheFile.get().asFile
        if (!cacheFile.isFile) {
            val message = "CMake did not generate its cache file at $cacheFile"
            throw problems.reporter.throwing(
                IllegalStateException(message),
                CACHE_FILE_NOT_GENERATED,
            ) {
                contextualLabel(message)
            }
        }
    }

    private companion object {
        val CACHE_FILE_NOT_GENERATED =
            ProblemId.create("cache-file-not-generated", "CMake cache file was not generated", PROBLEM_GROUP)
    }
}
