package io.technoirlab.cmake.import.internal

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.walk

internal class CMakeInstallScanner {
    fun scan(installDirectory: Path): CMakeInstallOutput {
        val includeDirectory = installDirectory.resolve(INCLUDE_DIRECTORY_NAME)
        val libraryDirectory = installDirectory.resolve(LIBRARY_DIRECTORY_NAME)
        return CMakeInstallOutput(
            includeDirectory = includeDirectory,
            libraryDirectory = libraryDirectory,
            headers = includeDirectory.findFiles { it.isHeader() }
                .map { it.relativePath(includeDirectory) },
            archives = libraryDirectory.findFiles { it.isStaticArchive() },
            pkgConfigFiles = libraryDirectory.findFiles { it.isPkgConfigFile() },
        )
    }

    private fun Path.findFiles(filter: (Path) -> Boolean): List<Path> = takeIf { it.isDirectory() }
        ?.walk()
        ?.filter { it.isRegularFile() && filter(it) }
        ?.toList()
        .orEmpty()

    private fun Path.isHeader(): Boolean {
        val name = fileName.toString()
        return HEADER_EXTENSIONS.any { name.endsWith(".$it") }
    }

    private fun Path.isStaticArchive(): Boolean {
        val name = fileName.toString()
        return STATIC_ARCHIVE_EXTENSIONS.any { name.endsWith(".$it") }
    }

    private fun Path.isPkgConfigFile(): Boolean {
        val name = fileName.toString()
        return PKG_CONFIG_FILE_EXTENSIONS.any { name.endsWith(".$it") }
    }

    companion object {
        const val INCLUDE_DIRECTORY_NAME = "include"
        const val LIBRARY_DIRECTORY_NAME = "lib"
        val STATIC_ARCHIVE_EXTENSIONS = setOf("a", "A", "lib", "LIB")
        val PKG_CONFIG_FILE_EXTENSIONS = setOf("pc", "PC")
        val HEADER_EXTENSIONS = setOf("h", "H", "hh", "HH", "hpp", "HPP", "hxx", "HXX")
    }
}
