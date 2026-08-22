package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.internal.normalizedPathString
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Extracts transitive linker options from an installed pkg-config file.
 */
internal class PkgConfigLinkerOptionsResolver(
    private val pkgConfigParser: PkgConfigParser = PkgConfigParser(),
) {
    fun resolve(archive: Path, pkgConfigFiles: List<Path>): List<String> {
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
        return options
            .withoutImportedLibrary(library, logicalLibraryDirectory)
            .toDirectLinkerOptions(candidate.path, "Libs") +
            privateOptions
                .withoutImportedLibrary(library, logicalLibraryDirectory)
                .toDirectLinkerOptions(candidate.path, "Libs.private")
    }

    private fun Path.parse(): ParsedPkgConfig? = runCatching {
        val metadata = pkgConfigParser.parse(
            readText(StandardCharsets.UTF_8),
            predefinedVariables = mapOf("pcfiledir" to parent.normalizedPathString()),
        ) ?: return@runCatching null
        ParsedPkgConfig(this, metadata, metadata.expandedFieldTokens("Libs"))
    }.getOrNull()

    private fun Path.library(): Library? {
        val fileName = fileName.toString()
        val nameWithoutExtension = when {
            fileName.endsWith(".a") -> fileName.dropLast(2)
            fileName.endsWith(".lib", ignoreCase = true) -> fileName.dropLast(4)
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

    private fun List<String>.toDirectLinkerOptions(pkgConfigFile: Path, fieldName: String): List<String> {
        val result = mutableListOf<String>()
        for (option in this) {
            when {
                option == "-pthread" -> result += "-lpthread"
                option.startsWith(LINKER_OPTION_PREFIX) -> {
                    val linkerOptions = option.removePrefix(LINKER_OPTION_PREFIX).split(',')
                    require(linkerOptions.none { it.isEmpty() }) {
                        "Malformed compiler-driver linker option '$option' in field '$fieldName' " +
                            "of pkg-config file '$pkgConfigFile': linker arguments must not be empty"
                    }
                    result += linkerOptions
                }
                else -> result += option
            }
        }
        return result
    }

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
    }
}
