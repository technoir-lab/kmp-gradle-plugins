package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.internal.LinkerKind
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Extracts transitive linker options from an installed pkg-config file.
 *
 * pkg-config files list options for the compiler driver. The resolver drops the options that link the archive itself, including
 * those that `-Wl,` and `-Xlinker` options wrap, and writes the rest in the form that the target's linker takes, as
 * [CompilerDriverOptions.toOptions] describes.
 */
internal class PkgConfigLinkerOptionsResolver(
    private val pkgConfigParser: PkgConfigParser = PkgConfigParser(),
) {
    fun resolve(archive: Path, pkgConfigFiles: List<Path>, linkerKind: LinkerKind): List<String> {
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
        val privateOptions = parsed.expandedFieldTokens("Libs.private")?.let(CompilerDriverOptions::parse) ?: return emptyList()
        val importedLibrary = importedLibraryReferences(library, candidate.logicalLibraryDirectory(archive, library))
        return options.without(importedLibrary).toOptions(linkerKind, candidate.fieldDescription("Libs")) +
            privateOptions.without(importedLibrary).toOptions(linkerKind, candidate.fieldDescription("Libs.private"))
    }

    private fun Path.parse(): ParsedPkgConfig? = runCatching {
        val metadata = pkgConfigParser.parse(readText(StandardCharsets.UTF_8), parent) ?: return@runCatching null
        ParsedPkgConfig(this, metadata, metadata.expandedFieldTokens("Libs")?.let(CompilerDriverOptions::parse))
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

    private fun CompilerDriverOptions.containsImportedLibrary(library: Library): Boolean =
        contains { arguments, index -> arguments.importedLibraryOptionLength(index, library) }

    private fun ParsedPkgConfig.logicalLibraryDirectory(archive: Path, library: Library): String? {
        if (publicOptions?.containsImportedLibrary(library) != true) return null
        if (path.parent?.parent?.normalize() != archive.parent?.normalize()) return null
        return metadata.expandedVariable("libdir")
    }

    private fun ParsedPkgConfig.fieldDescription(fieldName: String): String = "field '$fieldName' of pkg-config file '$path'"

    /**
     * Matches the options that link [library] or add its [logicalLibraryDirectory] to the library search paths.
     */
    private fun importedLibraryReferences(library: Library, logicalLibraryDirectory: String?): (List<String>, Int) -> Int? =
        { arguments, index ->
            arguments.importedLibraryOptionLength(index, library)
                ?: arguments.importedLibraryDirectoryOptionLength(index, logicalLibraryDirectory)
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
        val publicOptions: CompilerDriverOptions?,
    )
}
