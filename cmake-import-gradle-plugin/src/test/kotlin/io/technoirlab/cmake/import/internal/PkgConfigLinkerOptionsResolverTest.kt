package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

class PkgConfigLinkerOptionsResolverTest {
    private val resolver = PkgConfigLinkerOptionsResolver()

    @TempDir
    private lateinit var installDirectory: Path

    @Test
    fun `normalizes macOS compiler-driver options for the direct linker`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            prefix=/usr/local
            exec_prefix=${prefix}
            libdir=${exec_prefix}/lib

            Name: hello
            Libs: -L${libdir} -lhello -Wl,-framework,CoreMedia -Wl,-weak_framework,CoreHaptics -lpthread -lm
            Libs.private:
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly(
            "-L/usr/local/lib",
            "-framework",
            "CoreMedia",
            "-weak_framework",
            "CoreHaptics",
            "-lpthread",
            "-lm",
        )
    }

    @Test
    fun `combines Linux public and private linker options`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            libdir=/usr/local/lib
            Name: hello
            Libs: -L ${libdir} -lhello -pthread -lm
            Libs.private: -ldl
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly("-L", "/usr/local/lib", "-lpthread", "-lm", "-ldl")
    }

    @Test
    fun `expands wrapped linker options in place`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            """
            Name: hello
            Libs: -Wl,--as-needed,-z,defs -lhello -Wl,--no-as-needed
            Libs.private: -Wl,-rpath,/opt/hello/lib -ldl
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly(
            "--as-needed",
            "-z",
            "defs",
            "--no-as-needed",
            "-rpath",
            "/opt/hello/lib",
            "-ldl",
        )
    }

    @Test
    fun `fails explicitly for malformed wrapped public linker option`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            """
            Name: hello
            Libs: -lhello -Wl,-z,,defs -lm
            """.trimIndent(),
        )

        assertThatThrownBy {
            resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Malformed compiler-driver linker option '-Wl,-z,,defs' in field 'Libs' " +
                    "of pkg-config file '$pkgConfigFile': linker arguments must not be empty",
            )
    }

    @Test
    fun `fails explicitly for malformed wrapped private linker option`() {
        val pkgConfigFile = pkgConfigFile(
            "nested/hello.pc",
            """
            Name: hello
            Libs: -lhello -lm
            Libs.private: -ldl -Wl,
            """.trimIndent(),
        )

        assertThatThrownBy {
            resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Malformed compiler-driver linker option '-Wl,' in field 'Libs.private' " +
                    "of pkg-config file '$pkgConfigFile': linker arguments must not be empty",
            )
    }

    @Test
    fun `resolves MinGW system libraries`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            prefix=C:/hello
            libdir=${prefix}/lib
            Name: hello
            Libs: -L${libdir} -lhello -mwindows -lm -lkernel32 -luser32 -lgdi32 -lwinmm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly(
            "-LC:/hello/lib",
            "-mwindows",
            "-lm",
            "-lkernel32",
            "-luser32",
            "-lgdi32",
            "-lwinmm",
        )
    }

    @Test
    fun `expands recursive variables`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            prefix=/installation
            libdir=${prefix}/lib
            dependency_dir=${prefix}/dependencies
            Name: hello
            Libs: -L${libdir} -lhello -L${dependency_dir} -ldependency
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly(
            "-L/installation/lib",
            "-L/installation/dependencies",
            "-ldependency",
        )
    }

    @Test
    fun `selects pkg-config file by archive name case insensitively`() {
        val matching = pkgConfigFile(
            "HELLO.PC",
            $$"""
            libdir=/install/lib
            Name: hello
            Libs: -L${libdir} -lhello -lm
            """.trimIndent(),
        )
        val unrelated = pkgConfigFile(
            "other.pc",
            $$"""
            libdir=/install/lib
            Name: other
            Libs: -L${libdir} -lother -ldl
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("hello.LIB"), listOf(unrelated, matching))

        assertThat(options).containsExactly("-L/install/lib", "-lm")
    }

    @Test
    fun `selects conventionally prefixed pkg-config filename`() {
        val pkgConfigFile = pkgConfigFile(
            "libfoo.pc",
            """
            Name: foo
            Libs: -lfoo -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly("-lm")
    }

    @Test
    fun `selects pkg-config metadata by linker name for debug-postfixed archive`() {
        val pkgConfigFile = pkgConfigFile(
            "foo.pc",
            """
            Name: foo
            Libs: -lfood -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libfood.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly("-lm")
    }

    @Test
    fun `expands variables before splitting linker options`() {
        val pkgConfigFile = pkgConfigFile(
            "foo.pc",
            $$"""
            deps=-lm -ldl
            Name: foo
            Libs: -lfoo ${deps}
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly("-lm", "-ldl")
    }

    @Test
    fun `resolves linker options without a libdir variable`() {
        val pkgConfigFile = pkgConfigFile(
            "foo.pc",
            """
            Name: foo
            Libs: -lfoo -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly("-lm")
    }

    @Test
    fun `preserves search path used by remaining libraries`() {
        val pkgConfigFile = pkgConfigFile(
            "foo.pc",
            $$"""
            libdir=/install/lib
            Name: foo
            Libs: -L${libdir} -lfoo -lbar
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile))

        assertThat(options).containsExactly("-L/install/lib", "-lbar")
    }

    @Test
    fun `returns no options when matching metadata is ambiguous`() {
        val first = pkgConfigFile("one/hello.pc", validHelloPkgConfig("-lm"))
        val second = pkgConfigFile("two/HELLO.pc", validHelloPkgConfig("-ldl"))

        val options = resolver.resolve(archive("libhello.a"), listOf(first, second))

        assertThat(options).isEmpty()
    }

    @Test
    fun `returns no options when dependency requirements are present`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            libdir=/install/lib
            Name: hello
            Requires.private: dependency >= 1.0
            Libs: -L${libdir} -lhello -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).isEmpty()
    }

    @Test
    fun `returns no options for unsupported quoting`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            libdir=/install/lib
            Name: hello
            Libs: -L${libdir} -lhello "-framework Cocoa"
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).isEmpty()
    }

    @Test
    fun `returns no options for unresolved variables`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            libdir=/install/lib
            Name: hello
            Libs: -L${libdir} -lhello -L${missing}
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile))

        assertThat(options).isEmpty()
    }

    private fun validHelloPkgConfig(transitiveOption: String): String = $$"""
        libdir=/install/lib
        Name: hello
        Libs: -L${libdir} -lhello $$transitiveOption
    """.trimIndent()

    private fun archive(name: String): Path {
        val directory = (installDirectory / "lib").createDirectories()
        return directory / name
    }

    private fun pkgConfigFile(relativePath: String, content: String): Path {
        val file = installDirectory / "lib" / "pkgconfig" / relativePath
        file.parent.createDirectories()
        file.writeText(content)
        return file
    }
}
