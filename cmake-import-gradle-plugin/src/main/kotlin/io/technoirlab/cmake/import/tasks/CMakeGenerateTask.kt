package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.CMakeImportPlugin.Companion.PROBLEM_GROUP
import io.technoirlab.cmake.import.internal.CMakeRunner
import io.technoirlab.cmake.import.internal.CMakeTaskStateManager
import io.technoirlab.gradle.asPath
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

/**
 * Generates a CMake build system for a project.
 */
@Suppress("UnstableApiUsage")
@DisableCachingByDefault(because = "CMake manages non-relocatable build state")
abstract class CMakeGenerateTask @Inject internal constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
) : BaseCMakeTask() {

    @get:Input
    @get:Optional
    abstract val cmakeGenerator: Property<String>

    @get:Input
    abstract val cmakeBuildType: Property<String>

    @get:Input
    abstract val cmakeDefines: MapProperty<String, String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:Input
    internal abstract val sourceDirectoryPath: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val toolchainFile: RegularFileProperty

    @get:Input
    internal abstract val toolchainFilePath: Property<String>

    /**
     * CMake's non-relocatable build tree.
     */
    @get:LocalState
    abstract val configureDirectory: DirectoryProperty

    @get:Input
    internal abstract val configureDirectoryPath: Property<String>

    /**
     * CMake's cache is mutable local state and only acts as an up-to-date validity check.
     */
    @get:Internal
    abstract val cacheFile: RegularFileProperty

    /**
     * Records the reset identity and successful execution without overlapping CMake's build tree.
     */
    @get:OutputFile
    internal abstract val generateStateFile: RegularFileProperty

    // Workaround for https://github.com/gradle/gradle/issues/31958
    @get:Inject
    internal abstract val problems: Problems

    init {
        outputs.upToDateWhen {
            cacheFile.asFile.orNull?.isFile == true
        }
    }

    @TaskAction
    internal fun generate() {
        val sourceDir = sourceDirectory.get().asPath()
        val buildType = cmakeBuildType.get()
        val configureDirectory = configureDirectory.get().asPath()
        val toolchainFile = toolchainFile.get().asPath()
        val cacheFile = cacheFile.get().asFile
        val stateFile = generateStateFile.get().asPath()
        val stateManager = CMakeTaskStateManager()
        val resetIdentity = stateManager.calculateResetIdentity(
            generator = cmakeGenerator.orNull,
            buildType = buildType,
            definitions = cmakeDefines.get(),
            sourceDirectory = sourceDir,
            configureDirectory = configureDirectory,
            toolchainFile = toolchainFile,
        )
        val previousState = stateManager.readOrNull(stateFile)

        if (configureDirectory.exists() && (previousState?.resetIdentity != resetIdentity || !cacheFile.isFile)) {
            fileSystemOperations.delete {
                delete(configureDirectory)
            }
        }
        stateFile.deleteIfExists()

        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.generate(
            sourceDir = sourceDir,
            configureDir = configureDirectory,
            toolchainFile = toolchainFile,
            buildType = buildType,
            generator = cmakeGenerator.orNull,
            defines = cmakeDefines.get(),
        )

        if (!cacheFile.isFile) {
            val message = "CMake did not generate its cache file at $cacheFile"
            throw problems.reporter.throwing(
                IllegalStateException(message),
                CACHE_FILE_NOT_GENERATED,
            ) {
                contextualLabel(message)
            }
        }

        stateManager.write(stateFile, stateManager.create(resetIdentity))
    }

    internal companion object {
        val CACHE_FILE_NOT_GENERATED =
            ProblemId.create("cache-file-not-generated", "CMake cache file was not generated", PROBLEM_GROUP)
    }
}
