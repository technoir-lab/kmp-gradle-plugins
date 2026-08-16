package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import kotlin.io.path.div

class CInteropDefinitionGeneratorTest {
    private val generator = CInteropDefinitionGenerator()

    @Test
    fun `generates all definition properties in order`() {
        val includeDirectory = Path.of("/install/include")
        val archive = Path.of("/install/lib/libhello.a")

        val definition = generator.generate(
            packageName = "cmake.hello",
            headers = listOf(includeDirectory / "hello.h", includeDirectory / "nested/world.h"),
            includeDirectory = includeDirectory,
            archive = archive,
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            headers = /install/include/hello.h /install/include/nested/world.h
            compilerOpts = -I/install/include
            staticLibraries = libhello.a
            libraryPaths = /install/lib
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `normalizes paths`() {
        val includeDirectory = Path.of("/project/build/../include")
        val archive = Path.of("/project/build/../lib/nested/../libhello.a")

        val definition = generator.generate(
            packageName = "cmake.hello",
            headers = listOf(includeDirectory / "nested/../hello.h"),
            includeDirectory = includeDirectory,
            archive = archive,
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            headers = /project/include/hello.h
            compilerOpts = -I/project/include
            staticLibraries = libhello.a
            libraryPaths = /project/lib
            """.trimIndent() + "\n",
        )
    }

    @Test
    fun `omits headers property when headers are empty`() {
        val includeDirectory = Path.of("/install/include")
        val archive = Path.of("/install/lib/libhello.a")

        val definition = generator.generate(
            packageName = "cmake.hello",
            headers = emptyList(),
            includeDirectory = includeDirectory,
            archive = archive,
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
            packageName = packageName,
            headers = emptyList(),
            includeDirectory = Path.of("/install/include"),
            archive = Path.of("/install/lib/libhello.a"),
        )

        assertThat(definition.lineSequence().first()).isEqualTo("package = $expectedValue")
    }

    @Test
    fun `quotes special characters in every path property`() {
        val includeDirectory = Path.of("/install/include files#1")
        val header = includeDirectory / "hello\"world.h"
        val archive = Path.of("/install/library files#1/lib\"hello.a")

        val definition = generator.generate(
            packageName = "cmake.hello",
            headers = listOf(header),
            includeDirectory = includeDirectory,
            archive = archive,
        )

        assertThat(definition).isEqualTo(
            """
            package = cmake.hello
            headers = "/install/include files#1/hello\"world.h"
            compilerOpts = "-I/install/include files#1"
            staticLibraries = "lib\"hello.a"
            libraryPaths = "/install/library files#1"
            """.trimIndent() + "\n",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " \t\n"])
    fun `rejects blank package names`(packageName: String) {
        assertThatThrownBy {
            generator.generate(
                packageName = packageName,
                headers = emptyList(),
                includeDirectory = Path.of("/install/include"),
                archive = Path.of("/install/lib/libhello.a"),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("packageName must not be blank")
    }
}
