package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.internal.LinkerKind
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CompilerDriverOptionsTest {
    @Test
    fun `writes options as written for the compiler driver`() {
        val options = options("-Wl,--as-needed,-z,defs -lm -Xlinker -z -Xlinker now -Wl,-z,,defs -Wl, -pthread -Xlinker")

        val result = CompilerDriverOptions.parse(options).toOptions(LinkerKind.COMPILER_DRIVER, SOURCE)

        assertThat(result).containsExactlyElementsOf(options)
    }

    @Test
    fun `converts options for a GNU-style linker`() {
        val options = options("-Wl,--as-needed,-lm -Xlinker --no-as-needed -pthread -rdynamic -static-libgcc -static-libstdc++ -ldl")

        val result = CompilerDriverOptions.parse(options).toOptions(LinkerKind.GNU, SOURCE)

        assertThat(result).containsExactly("--as-needed", "-lm", "--no-as-needed", "-lpthread", "--export-dynamic", "-ldl")
    }

    @Test
    fun `converts options for the Apple linker`() {
        val options = options("-Wl,-framework,CoreMedia -Xlinker -dead_strip -pthread -rdynamic -shared-libgcc -lc++")

        val result = CompilerDriverOptions.parse(options).toOptions(LinkerKind.APPLE, SOURCE)

        assertThat(result).containsExactly("-framework", "CoreMedia", "-dead_strip", "-lpthread", "-export_dynamic", "-lc++")
    }

    @Test
    fun `fails for a linker when a wrapped argument is empty`() {
        val options = CompilerDriverOptions.parse(options("-lm -Wl,-z,,defs"))

        assertThatThrownBy { options.toOptions(LinkerKind.GNU, SOURCE) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Malformed compiler-driver linker option '-Wl,-z,,defs' in field 'Libs': linker arguments must not be empty")
    }

    @Test
    fun `fails for a linker when -Xlinker has no argument`() {
        val options = CompilerDriverOptions.parse(options("-lm -Xlinker"))

        assertThatThrownBy { options.toOptions(LinkerKind.APPLE, SOURCE) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("Malformed compiler-driver linker option '-Xlinker' in field 'Libs': linker argument is missing")
    }

    @Test
    fun `removes a forwarded argument with its -Xlinker option`() {
        val options = CompilerDriverOptions.parse(options("-lm -Xlinker -lhello -Xlinker -z -Xlinker now"))

        val result = options.without(::helloLibrary).toOptions(LinkerKind.COMPILER_DRIVER, SOURCE)

        assertThat(result).containsExactlyElementsOf(options("-lm -Xlinker -z -Xlinker now"))
    }

    @Test
    fun `keeps the remaining arguments of a -Wl, option`() {
        val options = CompilerDriverOptions.parse(options("-Wl,--as-needed,-lhello,--no-as-needed -lm"))

        val result = options.without(::helloLibrary).toOptions(LinkerKind.COMPILER_DRIVER, SOURCE)

        assertThat(result).containsExactly("-Wl,--as-needed,--no-as-needed", "-lm")
    }

    @Test
    fun `drops a -Wl, option that has no remaining arguments`() {
        val options = CompilerDriverOptions.parse(options("-Wl,-lhello -lm"))

        val result = options.without(::helloLibrary).toOptions(LinkerKind.COMPILER_DRIVER, SOURCE)

        assertThat(result).containsExactly("-lm")
    }

    @Test
    fun `removes an option whose arguments are forwarded separately`() {
        val options = CompilerDriverOptions.parse(options("-Xlinker -l -Xlinker hello -Wl,-l,hello -lm"))

        val result = options.without(::helloLibrary).toOptions(LinkerKind.GNU, SOURCE)

        assertThat(result).containsExactly("-lm")
    }

    @Test
    fun `matches compiler-driver and linker arguments separately`() {
        val options = CompilerDriverOptions.parse(options("-l -Xlinker hello"))

        val result = options.without(::helloLibrary).toOptions(LinkerKind.COMPILER_DRIVER, SOURCE)

        assertThat(result).containsExactlyElementsOf(options("-l -Xlinker hello"))
    }

    @Test
    fun `contains an option that a wrapped argument matches`() {
        val options = CompilerDriverOptions.parse(options("-lm -Wl,-lhello"))

        val contains = options.contains(::helloLibrary)

        assertThat(contains).isTrue()
    }

    @Test
    fun `does not contain options that no argument matches`() {
        val options = CompilerDriverOptions.parse(options("-lm -Wl,-lhello2 -Xlinker -l"))

        val contains = options.contains(::helloLibrary)

        assertThat(contains).isFalse()
    }

    private fun helloLibrary(arguments: List<String>, index: Int): Int? = when {
        arguments[index] == "-lhello" -> 1
        arguments[index] == "-l" && arguments.getOrNull(index + 1) == "hello" -> 2
        else -> null
    }

    private fun options(text: String): List<String> = text.split(' ')

    private companion object {
        const val SOURCE = "field 'Libs'"
    }
}
