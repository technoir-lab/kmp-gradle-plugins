package io.technoirlab.cmake.import.pkconfig

import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Extracts transitive linker options from an installed pkg-config file.
 *
 * pkg-config files list options for the compiler driver. Kotlin/Native links Android and MinGW binaries through the Clang
 * driver, which takes these options as they are, and binaries for other targets with the linker itself. For those other
 * targets, the resolver converts these compiler-driver options for the linker:
 * - `-Wl,<options>` and `-Xlinker <option>` become the linker options they wrap.
 * - `-pthread` becomes `-lpthread`.
 * - `-rdynamic` becomes `-export_dynamic` for Apple targets and `--export-dynamic` for Linux targets.
 * - `-static-libgcc`, `-shared-libgcc`, and `-static-libstdc++` are dropped: they choose how the compiler driver links its
 *   runtime libraries, which Kotlin/Native links itself.
 *
 * Other options reach the linker unchanged.
 */
internal class PkgConfigLinkerOptionsResolver(
    private val pkgConfigParser: PkgConfigParser = PkgConfigParser(),
) {
    fun resolve(archive: Path, pkgConfigFiles: List<Path>, target: KonanTarget): List<String> {
        val library = archive.library() ?: return emptyList()
        val parsedFiles = pkgConfigFiles.mapNotNull { it.parse() }
        val namedCandidates = parsedFiles.filter { it.path.matchesLibraryName(library.linkerName) }
        val candidates = namedCandidates.ifEmpty {
            parsedFiles.filter { parsedFile ->
                parsedFile.publicOptions?.containsImportedLibrary(library) == true
            }
        }
        if (candidates.size != 1) return emptyList()

        val candidate = candidates.single()
        val parsed = candidate.metadata
        if (parsed.field("Requires").orEmpty().isNotBlank()) return emptyList()
        if (parsed.field("Requires.private").orEmpty().isNotBlank()) return emptyList()

        val options = candidate.publicOptions ?: return emptyList()
        val privateOptions = parsed.expandedFieldTokens("Libs.private") ?: return emptyList()
        val logicalLibraryDirectory = candidate.logicalLibraryDirectory(archive, library)
        val publicLinkerOptions = options.withoutImportedLibrary(library, logicalLibraryDirectory)
        val privateLinkerOptions = privateOptions.withoutImportedLibrary(library, logicalLibraryDirectory)
        if (target.family in COMPILER_DRIVER_LINKED_FAMILIES) return publicLinkerOptions + privateLinkerOptions

        return publicLinkerOptions.toDirectLinkerOptions(target, candidate.path, "Libs") +
            privateLinkerOptions.toDirectLinkerOptions(target, candidate.path, "Libs.private")
    }

    private fun Path.parse(): ParsedPkgConfig? = runCatching {
        val metadata = pkgConfigParser.parse(readText(StandardCharsets.UTF_8), parent) ?: return@runCatching null
        ParsedPkgConfig(this, metadata, metadata.expandedFieldTokens("Libs"))
    }.getOrNull()

    private fun Path.library(): Library? {
        val fileName = fileName.toString()
        val nameWithoutExtension = when {
            fileName.endsWith(".a") || fileName.endsWith(".A") -> fileName.dropLast(2)
            fileName.endsWith(".lib") || fileName.endsWith(".LIB") -> fileName.dropLast(4)
            else -> return null
        }
        val linkerName = nameWithoutExtension.removeLibraryPrefix().takeIf { it.isNotEmpty() } ?: return null
        return Library(linkerName, fileName)
    }

    private fun Path.matchesLibraryName(libraryName: String): Boolean =
        nameWithoutExtension.removeLibraryPrefix().equals(libraryName, ignoreCase = true)

    private fun String.removeLibraryPrefix(): String = if (startsWith("lib", ignoreCase = true)) drop(3) else this

    private fun List<String>.containsImportedLibrary(library: Library): Boolean =
        indices.any { importedLibraryOptionLength(it, library) != null }

    private fun ParsedPkgConfig.logicalLibraryDirectory(archive: Path, library: Library): String? {
        if (publicOptions?.containsImportedLibrary(library) != true) return null
        if (path.parent?.parent?.normalize() != archive.parent?.normalize()) return null
        return metadata.expandedVariable("libdir")
    }

    private fun List<String>.withoutImportedLibrary(library: Library, logicalLibraryDirectory: String?): List<String> {
        val result = mutableListOf<String>()
        var index = 0
        while (index < size) {
            val importedLibraryOptionLength = importedLibraryOptionLength(index, library)
            val importedLibraryDirectoryOptionLength =
                importedLibraryDirectoryOptionLength(index, logicalLibraryDirectory)
            when {
                importedLibraryOptionLength != null -> index += importedLibraryOptionLength
                importedLibraryDirectoryOptionLength != null -> index += importedLibraryDirectoryOptionLength
                else -> result += this[index++]
            }
        }
        return result
    }

    private fun List<String>.toDirectLinkerOptions(target: KonanTarget, pkgConfigFile: Path, fieldName: String): List<String> {
        val result = mutableListOf<String>()
        val options = iterator()
        while (options.hasNext()) {
            val option = options.next()
            when {
                option == "-pthread" -> result += "-lpthread"
                option == "-rdynamic" -> result += if (target.family.isAppleFamily) "-export_dynamic" else "--export-dynamic"
                option in RUNTIME_LIBRARY_OPTIONS -> Unit
                option == "-Xlinker" -> {
                    require(options.hasNext()) {
                        malformedOptionMessage(option, fieldName, pkgConfigFile, "linker argument is missing")
                    }
                    result += options.next()
                }
                option.startsWith(LINKER_OPTION_PREFIX) -> {
                    val linkerOptions = option.removePrefix(LINKER_OPTION_PREFIX).split(',')
                    require(linkerOptions.none { it.isEmpty() }) {
                        malformedOptionMessage(option, fieldName, pkgConfigFile, "linker arguments must not be empty")
                    }
                    result += linkerOptions
                }
                else -> result += option
            }
        }
        return result
    }

    private fun malformedOptionMessage(option: String, fieldName: String, pkgConfigFile: Path, reason: String): String =
        "Malformed compiler-driver linker option '$option' in field '$fieldName' of pkg-config file '$pkgConfigFile': $reason"

    private fun List<String>.importedLibraryDirectoryOptionLength(index: Int, directory: String?): Int? {
        if (directory == null) return null
        val option = this[index]
        return when {
            option == "-L" && getOrNull(index + 1)?.samePkgConfigPath(directory) == true -> 2
            option.startsWith("-L") && option.drop(2).samePkgConfigPath(directory) -> 1
            else -> null
        }
    }

    private fun List<String>.importedLibraryOptionLength(index: Int, library: Library): Int? {
        val option = this[index]
        return when {
            option.equals("-l${library.linkerName}", ignoreCase = true) -> 1
            option == "-l" && getOrNull(index + 1)?.equals(library.linkerName, ignoreCase = true) == true -> 2
            option.startsWith("-l:") && option.drop(3).equals(library.fileName, ignoreCase = true) -> 1
            option.fileName().equals(library.fileName, ignoreCase = true) -> 1
            else -> null
        }
    }

    private fun String.fileName(): String = substringAfterLast('/').substringAfterLast('\\')

    private fun String.samePkgConfigPath(other: String): Boolean = replace('\\', '/').trimEnd('/') ==
        other.replace('\\', '/').trimEnd('/')

    private data class Library(
        val linkerName: String,
        val fileName: String,
    )

    private data class ParsedPkgConfig(
        val path: Path,
        val metadata: PkgConfigMetadata,
        val publicOptions: List<String>?,
    )

    private companion object {
        const val LINKER_OPTION_PREFIX = "-Wl,"

        /**
         * Target families whose binaries Kotlin/Native links through the Clang driver rather than with the linker itself.
         */
        val COMPILER_DRIVER_LINKED_FAMILIES = setOf(Family.ANDROID, Family.MINGW)

        /**
         * Compiler-driver options that choose how the driver links its runtime libraries, which Kotlin/Native links itself.
         */
        val RUNTIME_LIBRARY_OPTIONS = setOf("-static-libgcc", "-shared-libgcc", "-static-libstdc++")
    }
}
