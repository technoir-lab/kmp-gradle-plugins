package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.Path
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
        assertThat(output.pkgConfigFiles).isEmpty()
    }

    @Test
    fun `finds supported header extensions recursively and case insensitively`() {
        val includeDirectory = (installDirectory / "include").createDirectories()
        (includeDirectory / "hello.h").createFile()
        val nestedDirectory = (includeDirectory / "detail").createDirectories()
        val headers = listOf(
            Path("hello.h"),
            Path("detail/one.H"),
            Path("detail/two.hh"),
            Path("detail/three.hpp"),
            Path("detail/four.hxx"),
        )
        (nestedDirectory / "one.H").createFile()
        (nestedDirectory / "two.hh").createFile()
        (nestedDirectory / "three.hpp").createFile()
        (nestedDirectory / "four.hxx").createFile()

        val output = scanner.scan(installDirectory)

        assertThat(output.headers).containsExactlyInAnyOrderElementsOf(headers)
    }

    @Test
    fun `ignores non-header files in include directory`() {
        val includeDirectory = (installDirectory / "include").createDirectories()
        (includeDirectory / "hello.h").createFile()
        (includeDirectory / "hello.c").createFile()
        (includeDirectory / "hello.cpp").createFile()
        (includeDirectory / "hello.h.in").createFile()
        (includeDirectory / "README").createFile()

        val output = scanner.scan(installDirectory)

        assertThat(output.headers).containsExactly(Path("hello.h"))
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
    fun `finds pkg-config files recursively and case insensitively`() {
        val libraryDirectory = (installDirectory / "lib").createDirectories()
        val pkgConfigDirectory = (libraryDirectory / "pkgconfig").createDirectories()
        val pkgConfigFiles = listOf(
            (pkgConfigDirectory / "hello.pc").createFile(),
            (pkgConfigDirectory / "world.PC").createFile(),
        )
        (pkgConfigDirectory / "README").createFile()

        val output = scanner.scan(installDirectory)

        assertThat(output.pkgConfigFiles).containsExactlyInAnyOrderElementsOf(pkgConfigFiles)
    }

    @Test
    fun `ignores conventional paths that are not directories`() {
        (installDirectory / "include").createFile()
        (installDirectory / "lib").createFile()

        val output = scanner.scan(installDirectory)

        assertThat(output.headers).isEmpty()
        assertThat(output.archives).isEmpty()
        assertThat(output.pkgConfigFiles).isEmpty()
    }
}
