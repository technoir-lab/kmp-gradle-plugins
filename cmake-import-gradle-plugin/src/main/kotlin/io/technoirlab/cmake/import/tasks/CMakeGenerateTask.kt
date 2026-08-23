package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.CMakeImportPlugin.Companion.PROBLEM_GROUP
import io.technoirlab.cmake.import.internal.CMakeRunner
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
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import javax.inject.Inject
import kotlin.io.path.createParentDirectories
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

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

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val toolchainFile: RegularFileProperty

    /**
     * CMake's non-relocatable build tree.
     */
    @get:LocalState
    abstract val configureDirectory: DirectoryProperty

    @get:OutputFile
    abstract val cacheFile: RegularFileProperty

    /**
     * Directory recording successful completion and the active toolchain without overlapping CMake's build tree.
     */
    @get:OutputDirectory
    abstract val generateOutputDirectory: DirectoryProperty

    // Workaround for https://github.com/gradle/gradle/issues/31958
    @get:Inject
    internal abstract val problems: Problems

    @TaskAction
    internal fun generate() {
        val sourceDir = sourceDirectory.get().asFile.toPath()
        val buildType = cmakeBuildType.get()
        val configureDirectory = configureDirectory.get().asFile.toPath()
        val toolchainFile = toolchainFile.get().asFile.toPath()
        val toolchainFingerprint = fingerprint(toolchainFile)
        val toolchainFingerprintFile = generateOutputDirectory.get().asFile.toPath()
            .resolve(TOOLCHAIN_FINGERPRINT_FILE_NAME)

        if (configureDirectory.exists() && toolchainFingerprintFile.fingerprintOrNull() != toolchainFingerprint) {
            fileSystemOperations.delete {
                delete(configureDirectory)
            }
        }

        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.generate(
            sourceDir = sourceDir,
            configureDir = configureDirectory,
            toolchainFile = toolchainFile,
            buildType = buildType,
            generator = cmakeGenerator.orNull,
            defines = cmakeDefines.get(),
        )

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

        toolchainFingerprintFile.createParentDirectories().writeText(toolchainFingerprint)
    }

    private fun Path.fingerprintOrNull(): String? = takeIf { it.isRegularFile() }?.readText()

    private fun fingerprint(path: Path): String = HexFormat.of().formatHex(
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)),
    )

    internal companion object {
        const val TOOLCHAIN_FINGERPRINT_FILE_NAME = "toolchain.sha256"

        val CACHE_FILE_NOT_GENERATED =
            ProblemId.create("cache-file-not-generated", "CMake cache file was not generated", PROBLEM_GROUP)
    }
}
