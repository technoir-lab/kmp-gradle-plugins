package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Path

class CInteropDefinitionGeneratorTest {
    private val generator = CInteropDefinitionGenerator()
    private lateinit var fileSystem: FileSystem

    @BeforeEach
    fun createUnixFileSystem(@TempDir tempDirectory: Path) {
        val archive = tempDirectory.resolve("paths.zip")
        fileSystem = FileSystems.newFileSystem(
            URI.create("jar:${archive.toUri()}"),
            mapOf("create" to "true"),
        )
    }

    @AfterEach
    fun closeUnixFileSystem() {
        fileSystem.close()
    }

    @Test
    fun `sorts and deduplicates exact header filters`() {
        val includeDirectory = path("/install/include")
        val archive = path("/install/lib/libhello.a")

        val definition = generator.generate(
            CInteropDefinition(
                packageName = "cmake.hello",
                headers = listOf(path("nested/world.h"), path("hello.h")),
                headerFilter = listOf(
                    path("nested/world.h"),
                    path("detail/internal.hpp"),
                    path("hello.h"),
                    path("nested/world.h"),
                ),
                includeDirectory = includeDirectory,
                archive = archive,
            ),
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            headers = hello.h nested/world.h
            headerFilter = detail/internal.hpp hello.h nested/world.h
            compilerOpts = -I/install/include
            staticLibraries = libhello.a
            libraryPaths = /install/lib
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `normalizes installation paths`() {
        val includeDirectory = path("/project/build/../include")
        val archive = path("/project/build/../lib/nested/../libhello.a")

        val definition = generator.generate(
            CInteropDefinition(
                packageName = "cmake.hello",
                headers = listOf(path("nested/hello.h")),
                headerFilter = listOf(path("nested/hello.h")),
                includeDirectory = includeDirectory,
                archive = archive,
            ),
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            headers = nested/hello.h
            headerFilter = nested/hello.h
            compilerOpts = -I/project/include
            staticLibraries = libhello.a
            libraryPaths = /project/lib
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `omits headers property when headers are empty`() {
        val includeDirectory = path("/install/include")
        val archive = path("/install/lib/libhello.a")

        val definition = generator.generate(
            CInteropDefinition(
                packageName = "cmake.hello",
                headers = emptyList(),
                headerFilter = emptyList(),
                includeDirectory = includeDirectory,
                archive = archive,
            ),
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            compilerOpts = -I/install/include
            staticLibraries = libhello.a
            libraryPaths = /install/lib
            """.trimIndent() + "\n",
        )
    }

    @ParameterizedTest
    @CsvSource(
        value = [
            "cmake hello,\"cmake hello\"",
            "cmake#hello,\"cmake#hello\"",
            "cmake\"hello,\"cmake\\\"hello\"",
            "cmake\\ hello,\"cmake\\\\ hello\"",
        ],
    )
    fun `quotes and escapes special package names`(packageName: String, expectedValue: String) {
        val definition = generator.generate(
            CInteropDefinition(
                packageName = packageName,
                headers = emptyList(),
                headerFilter = emptyList(),
                includeDirectory = path("/install/include"),
                archive = path("/install/lib/libhello.a"),
            ),
        )

        assertThat(definition.lineSequence().first()).isEqualTo("package = $expectedValue")
    }

    @Test
    fun `quotes special characters in every path property`() {
        val includeDirectory = path("/install/include files#1")
        val archive = path("/install/library files#1/lib\"hello.a")

        val definition = generator.generate(
            CInteropDefinition(
                packageName = "cmake.hello",
                headers = listOf(path("hello\"world.h")),
                headerFilter = listOf(path("hello\"world.h")),
                includeDirectory = includeDirectory,
                archive = archive,
            ),
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            headers = "hello\"world.h"
            headerFilter = "hello\"world.h"
            compilerOpts = "-I/install/include files#1"
            staticLibraries = "lib\"hello.a"
            libraryPaths = "/install/library files#1"
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `generates and escapes linker options`() {
        val definition = generator.generate(
            CInteropDefinition(
                packageName = "cmake.hello",
                headers = emptyList(),
                headerFilter = emptyList(),
                includeDirectory = path("/install/include"),
                archive = path("/install/lib/libhello.a"),
                linkerOptions = listOf("-lm", "-L/library files", "-Wl,-framework,Cocoa"),
            ),
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            compilerOpts = -I/install/include
            linkerOpts = -lm "-L/library files" -Wl,-framework,Cocoa
            staticLibraries = libhello.a
            libraryPaths = /install/lib
            """.trimIndent() + "\n",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \t\n"])
    fun `rejects blank package names`(packageName: String) {
        assertThatThrownBy {
            generator.generate(
                CInteropDefinition(
                    packageName = packageName,
                    headers = emptyList(),
                    headerFilter = emptyList(),
                    includeDirectory = path("/install/include"),
                    archive = path("/install/lib/libhello.a"),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("packageName must not be blank")
    }

    private fun path(path: String): Path = fileSystem.getPath(path)
}
