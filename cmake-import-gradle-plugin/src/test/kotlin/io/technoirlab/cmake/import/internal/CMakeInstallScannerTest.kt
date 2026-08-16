package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.div

class CMakeInstallScannerTest {
    private val scanner = CMakeInstallScanner()

    @TempDir
    private lateinit var installDirectory: Path

    @Test
    fun `reports conventional directories when install output is missing`() {
        val output = scanner.scan(installDirectory)

        assertThat(output.includeDirectory).isEqualTo(installDirectory / "include")
        assertThat(output.libraryDirectory).isEqualTo(installDirectory / "lib")
        assertThat(output.headers).isEmpty()
        assertThat(output.archives).isEmpty()
    }

    @Test
    fun `finds headers recursively`() {
        val includeDirectory = (installDirectory / "include").createDirectories()
        val topLevelHeader = (includeDirectory / "hello.h").createFile()
        val nestedDirectory = (includeDirectory / "detail").createDirectories()
        val nestedHeader = (nestedDirectory / "world.hpp").createFile()

        val output = scanner.scan(installDirectory)

        assertThat(output.headers).containsExactlyInAnyOrder(topLevelHeader, nestedHeader)
    }

    @Test
    fun `finds static archives recursively and ignores other library files`() {
        val libraryDirectory = (installDirectory / "lib").createDirectories()
        val unixArchive = (libraryDirectory / "libhello.a").createFile()
        val nestedDirectory = (libraryDirectory / "nested").createDirectories()
        val windowsArchive = (nestedDirectory / "hello.LIB").createFile()
        (libraryDirectory / "libhello.so").createFile()
        (libraryDirectory / "libhello.a.debug").createFile()
        (nestedDirectory / "hello.dll").createFile()

        val output = scanner.scan(installDirectory)

        assertThat(output.archives).containsExactlyInAnyOrder(unixArchive, windowsArchive)
    }

    @Test
    fun `ignores conventional paths that are not directories`() {
        (installDirectory / "include").createFile()
        (installDirectory / "lib").createFile()

        val output = scanner.scan(installDirectory)

        assertThat(output.headers).isEmpty()
        assertThat(output.archives).isEmpty()
    }
}
