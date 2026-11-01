package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.internal.normalizedPathString
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
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

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.MACOS_ARM64)

        assertThat(options).containsExactly(
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

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly("-lpthread", "-lm", "-ldl")
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

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

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
    fun `converts compiler-driver options for the Linux linker`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            """
            Name: hello
            Libs: -lhello -rdynamic -static-libgcc -Xlinker --as-needed -lm -Xlinker --no-as-needed
            Libs.private: -shared-libgcc -static-libstdc++ -ldl
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly("--export-dynamic", "--as-needed", "-lm", "--no-as-needed", "-ldl")
    }

    @Test
    fun `converts compiler-driver options for the Apple linker`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            """
            Name: hello
            Libs: -lhello -rdynamic -static-libgcc -Xlinker -framework -Xlinker CoreMedia -lm
            Libs.private: -shared-libgcc -static-libstdc++ -lc++
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.MACOS_ARM64)

        assertThat(options).containsExactly("-export_dynamic", "-framework", "CoreMedia", "-lm", "-lc++")
    }

    @ParameterizedTest
    @MethodSource("compilerDriverTargets")
    fun `keeps compiler-driver options for targets that link through the compiler driver`(target: KonanTarget) {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            """
            Name: hello
            Libs: -Wl,--as-needed,-z,defs -lhello -pthread -rdynamic -Wl,--no-as-needed
            Libs.private: -Wl,-rpath,/opt/hello/lib -Xlinker -z -Xlinker now -static-libgcc -ldl
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), target)

        assertThat(options).containsExactly(
            "-Wl,--as-needed,-z,defs",
            "-pthread",
            "-rdynamic",
            "-Wl,--no-as-needed",
            "-Wl,-rpath,/opt/hello/lib",
            "-Xlinker",
            "-z",
            "-Xlinker",
            "now",
            "-static-libgcc",
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
            resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)
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
            resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Malformed compiler-driver linker option '-Wl,' in field 'Libs.private' " +
                    "of pkg-config file '$pkgConfigFile': linker arguments must not be empty",
            )
    }

    @Test
    fun `fails explicitly for a forwarded linker option without its argument`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            """
            Name: hello
            Libs: -lhello -lm -Xlinker
            Libs.private: -ldl
            """.trimIndent(),
        )

        assertThatThrownBy {
            resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage(
                "Malformed compiler-driver linker option '-Xlinker' in field 'Libs' " +
                    "of pkg-config file '$pkgConfigFile': linker argument is missing",
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

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.MINGW_X64)

        assertThat(options).containsExactly(
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
            root=/installation
            libdir=${root}/lib
            dependency_dir=${root}/dependencies
            Name: hello
            Libs: -L${libdir} -lhello -L${dependency_dir} -ldependency
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly(
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

        val options = resolver.resolve(archive("hello.LIB"), listOf(unrelated, matching), KonanTarget.MINGW_X64)

        assertThat(options).containsExactly("-lm")
    }

    @Test
    fun `does not accept mixed-case archive suffixes`() {
        val matching = pkgConfigFile(
            "hello.pc",
            $$"""
            libdir=/install/lib
            Name: hello
            Libs: -L${libdir} -lhello -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("hello.LiB"), listOf(matching), KonanTarget.MINGW_X64)

        assertThat(options).isEmpty()
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

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

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

        val options = resolver.resolve(archive("libfood.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

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

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

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

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly("-lm")
    }

    @Test
    fun `strips logical library directory while preserving external search paths`() {
        val pkgConfigFile = pkgConfigFile(
            "foo.pc",
            $$"""
            prefix=/install
            libdir=${prefix}/lib
            dependency_libdir=/opt/dependency/lib
            Name: foo
            Libs: -L${libdir}/ -lfoo -L ${dependency_libdir} -lbar
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly("-L", "/opt/dependency/lib", "-lbar")
    }

    @Test
    fun `preserves libdir option when metadata is not installed beside the archive`() {
        val pkgConfigFile = pkgConfigFile(
            "share/pkgconfig/foo.pc",
            $$"""
            libdir=/install/lib
            Name: foo
            Libs: -L${libdir} -lfoo -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libfoo.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly("-L/install/lib", "-lm")
    }

    @Test
    fun `strips portable logical libdir when staged install path contains spaces`() {
        installDirectory = installDirectory.resolve("staged install")
        val pkgConfigFile = pkgConfigFile(
            "foo.pc",
            $$"""
            prefix=C:/Foo
            libdir=${prefix}/lib
            Name: foo
            Libs: -L${libdir}/ -lfoo -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("foo.lib"), listOf(pkgConfigFile), KonanTarget.MINGW_X64)

        assertThat(options).containsExactly("-lm")
    }

    @Test
    fun `relocates prefix to the staged installation`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            prefix=C:/configured prefix/hello
            exec_prefix=${prefix}
            libdir=${exec_prefix}/lib
            dependency_dir=${prefix}/dependencies
            Name: hello
            Libs: -L${libdir} -lhello -L${dependency_dir} -mwindows -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.MINGW_X64)

        assertThat(options).containsExactly(
            "-L${installDirectory.normalizedPathString()}/dependencies",
            "-mwindows",
            "-lm",
        )
    }

    @Test
    fun `relocates absolute directories below the original prefix`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            prefix=C:/configured prefix/hello/
            libdir=C:/configured prefix/hello/lib
            dependency_libdir=C:/configured prefix/hello/dependencies/lib
            Name: hello
            Libs: -L${libdir} -lhello -L${dependency_libdir} -ldependency
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.MINGW_X64)

        assertThat(options).containsExactly(
            "-L${installDirectory.normalizedPathString()}/dependencies/lib",
            "-ldependency",
        )
    }

    @Test
    fun `does not relocate directories beside the original prefix`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            prefix=/configured/hello
            libdir=/configured/hello/lib
            sibling_libdir=/configured/hello2/lib
            Name: hello
            Libs: -L${libdir} -lhello -L${sibling_libdir} -lsibling
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly("-L/configured/hello2/lib", "-lsibling")
    }

    @Test
    fun `keeps configured prefix outside a pkgconfig directory`() {
        val pkgConfigFile = pkgConfigFile(
            "nested/hello.pc",
            $$"""
            prefix=/configured/hello
            libdir=${prefix}/lib
            Name: hello
            Libs: -L${libdir} -lhello -lm
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly("-L/configured/hello/lib", "-lm")
    }

    @Test
    fun `keeps pcfiledir paths containing spaces in one option`() {
        installDirectory = installDirectory.resolve("staged install")
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            root=${pcfiledir}/../..
            libdir=${root}/lib
            Name: hello
            Libs: -L${libdir} -lhello -L${root}/dependencies/lib -ldependency
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly(
            "-L${(installDirectory / "lib/pkgconfig").normalizedPathString()}/../../dependencies/lib",
            "-ldependency",
        )
    }

    @Test
    fun `keeps quoted relocated paths containing spaces in one option`() {
        installDirectory = installDirectory.resolve("staged install")
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            prefix=/configured/hello
            libdir=${prefix}/lib
            Name: hello
            Libs: -L"${libdir}" -lhello "-L${prefix}/dependencies/lib" -ldependency
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly(
            "-L${installDirectory.normalizedPathString()}/dependencies/lib",
            "-ldependency",
        )
    }

    @Test
    fun `honors quoted and escaped linker options`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            """
            Name: hello
            Libs: -lhello "-L/opt/dependency dir/lib" -L/opt/other\ dir/lib '-Wl,-rpath,/opt/rpath dir' -ldependency
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).containsExactly(
            "-L/opt/dependency dir/lib",
            "-L/opt/other dir/lib",
            "-rpath",
            "/opt/rpath dir",
            "-ldependency",
        )
    }

    @Test
    fun `returns no options when matching metadata is ambiguous`() {
        val first = pkgConfigFile("one/hello.pc", validHelloPkgConfig("-lm"))
        val second = pkgConfigFile("two/HELLO.pc", validHelloPkgConfig("-ldl"))

        val options = resolver.resolve(archive("libhello.a"), listOf(first, second), KonanTarget.LINUX_X64)

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

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).isEmpty()
    }

    @Test
    fun `returns no options for unterminated quoting`() {
        val pkgConfigFile = pkgConfigFile(
            "hello.pc",
            $$"""
            libdir=/install/lib
            Name: hello
            Libs: -L${libdir} -lhello "-framework Cocoa
            """.trimIndent(),
        )

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.MACOS_ARM64)

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

        val options = resolver.resolve(archive("libhello.a"), listOf(pkgConfigFile), KonanTarget.LINUX_X64)

        assertThat(options).isEmpty()
    }

    @Suppress("ktlint:standard:function-signature")
    private fun validHelloPkgConfig(transitiveOption: String): String =
        $$"""
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

    private companion object {
        @JvmStatic
        fun compilerDriverTargets() = listOf(KonanTarget.ANDROID_ARM64, KonanTarget.MINGW_X64)
    }
}
