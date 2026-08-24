package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.CMakeImportPlugin.Companion.PROBLEM_GROUP
import io.technoirlab.cmake.import.internal.CInteropDefinition
import io.technoirlab.cmake.import.internal.CInteropDefinitionGenerator
import io.technoirlab.cmake.import.internal.CMakeInstallOutput
import io.technoirlab.cmake.import.internal.CMakeInstallScanner.Companion.INCLUDE_DIRECTORY_NAME
import io.technoirlab.cmake.import.internal.CMakeInstallScanner.Companion.LIBRARY_DIRECTORY_NAME
import io.technoirlab.cmake.import.internal.portablePathString
import io.technoirlab.cmake.import.pkconfig.PkgConfigLinkerOptionsResolver
import io.technoirlab.gradle.asPath
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.problems.Problem
import org.gradle.api.problems.ProblemId
import org.gradle.api.problems.Problems
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import javax.inject.Inject
import kotlin.io.path.createParentDirectories
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * Generates a Kotlin/Native C-interop definition from a staged CMake installation.
 */
@Suppress("UnstableApiUsage")
@DisableCachingByDefault(because = "C-interop definitions contain absolute installation paths")
abstract class CMakeGenerateCInteropDefinitionTask @Inject internal constructor() : DefaultTask() {
    @get:Internal
    abstract val cmakeComponent: Property<String>

    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val includedHeaders: SetProperty<String>

    @get:Internal
    abstract val installDirectory: DirectoryProperty

    @get:Input
    internal abstract val installDirectoryPath: Property<String>

    @get:Input
    internal abstract val installedHeaderPaths: ListProperty<String>

    @get:Input
    internal abstract val installedArchivePaths: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    internal abstract val installedPkgConfigFiles: ConfigurableFileCollection

    @get:OutputFile
    abstract val definitionFile: RegularFileProperty

    // Workaround for https://github.com/gradle/gradle/issues/31958
    @get:Inject
    internal abstract val problems: Problems

    @TaskAction
    internal fun generate() {
        val component = cmakeComponent.orNull
        val includedHeaders = includedHeaders.get()
        val installDirectory = installDirectory.get().asPath()
        val output = CMakeInstallOutput(
            includeDirectory = installDirectory.resolve(INCLUDE_DIRECTORY_NAME),
            libraryDirectory = installDirectory.resolve(LIBRARY_DIRECTORY_NAME),
            headers = installedHeaderPaths.get().map(Path::of),
            archives = installedArchivePaths.get().map(installDirectory::resolve),
            pkgConfigFiles = installedPkgConfigFiles.files.map { it.toPath() }.sorted(),
        )
        output.validate(component, includedHeaders)

        val archive = output.archives.single()
        val definitionFile = definitionFile.get().asPath().createParentDirectories()
        val pkgConfigLinkerOptionsResolver = PkgConfigLinkerOptionsResolver()
        definitionFile.writeText(
            CInteropDefinitionGenerator().generate(
                CInteropDefinition(
                    packageName = packageName.get(),
                    headers = filterHeaders(output.headers, includedHeaders),
                    headerFilter = output.headers,
                    includeDirectory = output.includeDirectory,
                    archive = archive,
                    linkerOptions = pkgConfigLinkerOptionsResolver.resolve(archive, output.pkgConfigFiles),
                ),
            ),
            StandardCharsets.UTF_8,
        )
    }

    private fun CMakeInstallOutput.validate(component: String?, includedHeaders: Set<String>) {
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

        val missingHeaders = (includedHeaders - headers.map { it.portablePathString() }.toSet()).sorted()
        if (missingHeaders.isNotEmpty()) {
            foundProblems += problems.reporter.create(CONFIGURED_PUBLIC_HEADERS_NOT_INSTALLED) {
                contextualLabel(
                    "CMake $componentDescription did not install configured public header(s) " +
                        "${missingHeaders.joinToString()} at the expected include-relative path(s) " +
                        "under installation include directory $includeDirectory",
                )
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

    private fun filterHeaders(headers: List<Path>, includedHeaders: Set<String>): List<Path> = if (includedHeaders.isNotEmpty()) {
        headers.filter { it.portablePathString() in includedHeaders }
    } else {
        headers
    }

    private companion object {
        val PUBLIC_HEADERS_DIRECTORY_NOT_INSTALLED =
            ProblemId.create("public-headers-directory-not-installed", "Public headers directory was not installed", PROBLEM_GROUP)

        val NO_PUBLIC_HEADERS_INSTALLED =
            ProblemId.create("no-public-headers-installed", "No public headers were installed", PROBLEM_GROUP)

        val CONFIGURED_PUBLIC_HEADERS_NOT_INSTALLED =
            ProblemId.create("configured-public-headers-not-installed", "Configured public headers were not installed", PROBLEM_GROUP)

        val STATIC_ARCHIVE_DIRECTORY_NOT_INSTALLED =
            ProblemId.create("static-archive-directory-not-installed", "Static archive directory was not installed", PROBLEM_GROUP)

        val UNEXPECTED_STATIC_ARCHIVE_COUNT =
            ProblemId.create("unexpected-static-archive-count", "Unexpected number of static archives installed", PROBLEM_GROUP)
    }
}
