package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class LinkerOptionsArrangerTest {
    private val arranger = LinkerOptionsArranger()

    @ParameterizedTest
    @MethodSource("appleTargets")
    fun `autolinks a strong copy of each weak framework in its place`(target: KonanTarget) {
        val linkerOptions = options(
            "-framework CoreMedia -weak_framework UniformTypeIdentifiers -framework IOKit -weak_framework CoreHaptics -lpthread -lm",
        )

        val arranged = arranger.arrange(linkerOptions, target)

        assertThat(arranged).containsExactlyElementsOf(
            options(
                "-framework CoreMedia -framework UniformTypeIdentifiers -framework IOKit -framework CoreHaptics -lpthread -lm " +
                    "-weak_framework UniformTypeIdentifiers -weak_framework CoreHaptics",
            ),
        )
    }

    @Test
    fun `autolinks a strong copy of each weak library in its place`() {
        val linkerOptions = options("-lz -weak-lextra -lc++")

        val arranged = arranger.arrange(linkerOptions, KonanTarget.MACOS_ARM64)

        assertThat(arranged).containsExactlyElementsOf(options("-lz -lextra -lc++ -weak-lextra"))
    }

    @Test
    fun `moves options that cannot be autolinked after autolinkable options`() {
        val linkerOptions = options(
            "-L/opt/deps/lib -lz -F/opt/deps/frameworks -framework Extra -rpath /opt/deps/lib /opt/deps/lib/libother.a -lc++",
        )

        val arranged = arranger.arrange(linkerOptions, KonanTarget.MACOS_ARM64)

        assertThat(arranged).containsExactlyElementsOf(
            options("-lz -framework Extra -lc++ -L/opt/deps/lib -F/opt/deps/frameworks -rpath /opt/deps/lib /opt/deps/lib/libother.a"),
        )
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "-lazy_framework Extra",
            "-lazy_library /opt/deps/lib/libextra.dylib",
            "-lazy-lextra",
            "-ld_classic",
            "-ld_new",
            "-ld_prime",
            "-load_hidden /opt/deps/lib/libextra.a",
            "-lto_library /opt/deps/lib/libLTO.dylib",
        ],
    )
    fun `does not autolink linker options that start with -l but name no library`(option: String) {
        val linkerOptions = options("-lz $option -lc++")

        val arranged = arranger.arrange(linkerOptions, KonanTarget.MACOS_ARM64)

        assertThat(arranged).containsExactlyElementsOf(options("-lz -lc++ $option"))
    }

    @Test
    fun `does not autolink incomplete options`() {
        val linkerOptions = options("-l extra -weak-l -lz -weak_framework")

        val arranged = arranger.arrange(linkerOptions, KonanTarget.MACOS_ARM64)

        assertThat(arranged).containsExactlyElementsOf(options("-lz -l extra -weak-l -weak_framework"))
    }

    @Test
    fun `keeps autolinkable options unchanged`() {
        val linkerOptions = options("-framework Foundation -lz -framework Metal -lc++")

        val arranged = arranger.arrange(linkerOptions, KonanTarget.MACOS_ARM64)

        assertThat(arranged).containsExactlyElementsOf(linkerOptions)
    }

    @ParameterizedTest
    @MethodSource("otherTargets")
    fun `keeps the original order for other targets`(target: KonanTarget) {
        val linkerOptions = options("-L/opt/deps/lib --whole-archive -lextra --no-whole-archive -lpthread -lm")

        val arranged = arranger.arrange(linkerOptions, target)

        assertThat(arranged).containsExactlyElementsOf(linkerOptions)
    }

    private fun options(text: String): List<String> = text.split(' ')

    private companion object {
        // JUnit requires static @MethodSource factories.
        @JvmStatic
        fun appleTargets() = listOf(
            KonanTarget.MACOS_ARM64,
            KonanTarget.IOS_ARM64,
            KonanTarget.TVOS_SIMULATOR_ARM64,
            KonanTarget.WATCHOS_DEVICE_ARM64,
        )

        @JvmStatic
        fun otherTargets() = listOf(KonanTarget.LINUX_X64, KonanTarget.MINGW_X64, KonanTarget.ANDROID_ARM64)
    }
}
