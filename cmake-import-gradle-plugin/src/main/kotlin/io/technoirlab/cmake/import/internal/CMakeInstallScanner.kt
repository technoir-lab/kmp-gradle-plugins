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
        return HEADER_EXTENSIONS.any { name.endsWith(".$it", ignoreCase = true) }
    }

    private fun Path.isStaticArchive(): Boolean {
        val name = fileName.toString()
        return name.endsWith(".a") || name.endsWith(".lib", ignoreCase = true)
    }

    private fun Path.isPkgConfigFile(): Boolean = fileName.toString().endsWith(".pc", ignoreCase = true)

    private companion object {
        private const val INCLUDE_DIRECTORY_NAME = "include"
        private const val LIBRARY_DIRECTORY_NAME = "lib"
        private val HEADER_EXTENSIONS = setOf("h", "hh", "hpp", "hxx")
    }
}
