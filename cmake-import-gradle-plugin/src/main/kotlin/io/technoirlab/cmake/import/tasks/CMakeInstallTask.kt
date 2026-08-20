package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.CMakeImportPlugin.Companion.PROBLEM_GROUP
import io.technoirlab.cmake.import.internal.CInteropDefinition
import io.technoirlab.cmake.import.internal.CInteropDefinitionGenerator
import io.technoirlab.cmake.import.internal.CMakeInstallOutput
import io.technoirlab.cmake.import.internal.CMakeInstallScanner
import io.technoirlab.cmake.import.internal.CMakeRunner
import io.technoirlab.cmake.import.internal.PkgConfigLinkerOptionsResolver
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.problems.Problem
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * Installs a built CMake target and generates its C-interop definition.
 */
@Suppress("UnstableApiUsage")
@DisableCachingByDefault(because = "CMake manages non-relocatable build state")
internal abstract class CMakeInstallTask @Inject constructor(
    private val execOperations: ExecOperations,
    private val fileSystemOperations: FileSystemOperations,
    private val problems: Problems,
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val projectDirectory: DirectoryProperty

    @get:Input
    abstract val targetName: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val buildType: Property<String>

    @get:Input
    @get:Optional
    abstract val installComponent: Property<String>

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

    @get:OutputFile
    abstract val definitionFile: RegularFileProperty

    @TaskAction
    fun install() {
        val buildType = buildType.get()
        val packageName = packageName.get()
        val installComponent = installComponent.orNull
        val configureDirectory = configureDirectory.get().asFile.toPath()
        val installDirectory = installDirectory.get().asFile.toPath()

        fileSystemOperations.delete {
            delete(installDirectory)
        }
        val cmakeRunner = CMakeRunner(execOperations)
        cmakeRunner.install(configureDirectory, installDirectory, buildType, installComponent)

        val output = CMakeInstallScanner().scan(installDirectory)
        output.validate(installComponent)
        val archive = output.archives.single()

        val definitionFile = definitionFile.get().asFile.toPath().createParentDirectories()
        val pkgConfigLinkerOptionsResolver = PkgConfigLinkerOptionsResolver()
        definitionFile.writeText(
            CInteropDefinitionGenerator().generate(
                CInteropDefinition(
                    packageName = packageName,
                    headers = output.headers,
                    includeDirectory = output.includeDirectory,
                    archive = archive,
                    linkerOptions = pkgConfigLinkerOptionsResolver.resolve(archive, output.pkgConfigFiles),
                ),
            ),
            StandardCharsets.UTF_8,
        )
    }

    private fun CMakeInstallOutput.validate(component: String?) {
        val foundProblems = mutableListOf<Problem>()
        val componentDescription = component?.let { "component '$it'" } ?: "project"
        if (!includeDirectory.isDirectory()) {
            foundProblems += problems.reporter.create(PUBLIC_HEADERS_DIRECTORY_NOT_INSTALLED) {
                contextualLabel("CMake $componentDescription did not install public headers to $includeDirectory")
            }
        } else if (headers.isEmpty()) {
            foundProblems += problems.reporter.create(NO_PUBLIC_HEADERS_INSTALLED) {
                contextualLabel("CMake $componentDescription installed no public headers to $includeDirectory")
            }
        }
        if (!libraryDirectory.isDirectory()) {
            foundProblems += problems.reporter.create(STATIC_ARCHIVE_DIRECTORY_NOT_INSTALLED) {
                contextualLabel("CMake $componentDescription did not install a static archive to $libraryDirectory")
            }
        } else if (archives.size != 1) {
            foundProblems += problems.reporter.create(UNEXPECTED_STATIC_ARCHIVE_COUNT) {
                contextualLabel(
                    "CMake $componentDescription must install exactly one static archive to " +
                        "$libraryDirectory, but found: ${archives.joinToString()}",
                )
            }
        }

        if (foundProblems.isNotEmpty()) {
            throw problems.reporter.throwing(
                IllegalStateException("CMake $componentDescription installation validation failed"),
                foundProblems,
            )
        }
    }

    private companion object {
        val PUBLIC_HEADERS_DIRECTORY_NOT_INSTALLED =
            ProblemId.create("public-headers-directory-not-installed", "Public headers directory was not installed", PROBLEM_GROUP)

        val NO_PUBLIC_HEADERS_INSTALLED =
            ProblemId.create("no-public-headers-installed", "No public headers were installed", PROBLEM_GROUP)

        val STATIC_ARCHIVE_DIRECTORY_NOT_INSTALLED =
            ProblemId.create("static-archive-directory-not-installed", "Static archive directory was not installed", PROBLEM_GROUP)

        val UNEXPECTED_STATIC_ARCHIVE_COUNT =
            ProblemId.create("unexpected-static-archive-count", "Unexpected number of static archives installed", PROBLEM_GROUP)
    }
}
