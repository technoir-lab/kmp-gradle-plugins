package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Template
import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Verbatim
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ArgumentSplitterTest {
    private val splitter = ArgumentSplitter()

    @Test
    fun `splits template text at whitespace`() {
        val parts = listOf(Template(" -lfoo  -lbar\t-lbaz\n"))

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly("-lfoo", "-lbar", "-lbaz")
    }

    @Test
    fun `keeps single-quoted text as written`() {
        val parts = listOf(Template("""'-L/opt/dependency dir' 'back\slash "double"'"""))

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly("-L/opt/dependency dir", "back\\slash \"double\"")
    }

    @Test
    fun `unescapes only backslashes and double quotes within double quotes`() {
        val parts = listOf(Template("""-L"/opt/dependency dir" "quoted \"text\"" "back\\slash" "kept\backslash" "'single'""""))

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly(
            "-L/opt/dependency dir",
            "quoted \"text\"",
            "back\\slash",
            "kept\\backslash",
            "'single'",
        )
    }

    @Test
    fun `unescapes characters outside quotes`() {
        val parts = listOf(Template("""-L/opt/dependency\ dir \"quoted\" back\\slash"""))

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly("-L/opt/dependency dir", "\"quoted\"", "back\\slash")
    }

    @Test
    fun `joins adjacent quoted and unquoted text into one argument`() {
        val parts = listOf(Template("""-L"/opt/dependency dir"/lib'/sub dir'"""))

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly("-L/opt/dependency dir/lib/sub dir")
    }

    @Test
    fun `appends verbatim text to the current argument`() {
        val parts = listOf(
            Template("-L"),
            Verbatim("/staged dir"),
            Template("/lib -lm "),
            Verbatim("/other dir"),
        )

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly("-L/staged dir/lib", "-lm", "/other dir")
    }

    @Test
    fun `keeps quotes and backslashes of verbatim text within quotes`() {
        val parts = listOf(
            Template("-L\""),
            Verbatim("""/staged "dir" 'single' back\slash"""),
            Template("/lib\""),
        )

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly("-L/staged \"dir\" 'single' back\\slash/lib")
    }

    @Test
    fun `drops empty arguments`() {
        val parts = listOf(Template("""-lm "" '' -lc"""))

        val arguments = splitter.split(parts)

        assertThat(arguments).containsExactly("-lm", "-lc")
    }

    @Test
    fun `returns no arguments for blank text`() {
        val parts = listOf(Template(" \t "))

        val arguments = splitter.split(parts)

        assertThat(arguments).isEmpty()
    }

    @ParameterizedTest
    @ValueSource(strings = ["\"-lm", "'-lm", "-lm\\", "\"-lm\\"])
    fun `rejects text that ends within a quote or escape`(text: String) {
        val parts = listOf(Template(text))

        val arguments = splitter.split(parts)

        assertThat(arguments).isNull()
    }
}
