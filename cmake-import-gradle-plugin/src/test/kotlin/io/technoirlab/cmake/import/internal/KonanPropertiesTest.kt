package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.data.MapEntry.entry
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class KonanPropertiesTest {
    @Test
    fun `uses compilation overrides when there are no binaries`() {
        val compilationOverrides = mapOf("key" to "compilation")

        val overrides = KonanProperties.select(compilationOverrides, emptyList())

        assertThat(overrides).isEqualTo(compilationOverrides)
    }

    @Test
    fun `loads properties and applies overrides`(@TempDir temporaryDirectory: Path) {
        val propertiesFile = temporaryDirectory.resolve("konan.properties")
        propertiesFile.writeText(
            """
            retained=distribution
            overridden=distribution
            """.trimIndent(),
        )

        val properties = KonanProperties.load(
            propertiesFile,
            mapOf(
                "overridden" to "override",
                "added" to "override",
            ),
        )

        assertThat(properties.stringPropertyNames()).containsExactlyInAnyOrder("retained", "overridden", "added")
        assertThat(properties.getProperty("retained")).isEqualTo("distribution")
        assertThat(properties.getProperty("overridden")).isEqualTo("override")
        assertThat(properties.getProperty("added")).isEqualTo("override")
    }

    @Test
    fun `mirrors common target compilation native link and build type aggregation`() {
        val common = "-Xoverride-konan-properties=commonOnly=common;overridden=common"
        val target = "-Xoverride-konan-properties=targetOnly=target;overridden=target"
        val compilation = "-Xoverride-konan-properties=compilationOnly=compilation;overridden=compilation"
        val nativeLink = "-Xoverride-konan-properties=nativeLinkOnly=nativeLink;overridden=nativeLink"
        val debug = "-Xoverride-konan-properties=debugOnly=debug;overridden=debug"
        val release = "-Xoverride-konan-properties=releaseOnly=release;overridden=release"
        val compilationArguments = listOf(common, target, compilation)
        val binaryArguments = compilationArguments + nativeLink
        val compilationOverrides = KonanProperties.parse(compilationArguments)
        val nativeLinkOverrides = KonanProperties.parse(binaryArguments)
        val debugOverrides = KonanProperties.select(
            effectiveCompilationOverrides = compilationOverrides,
            effectiveBinaryOverrides = listOf(KonanProperties.parse(binaryArguments + debug)),
        )
        val releaseOverrides = KonanProperties.select(
            effectiveCompilationOverrides = compilationOverrides,
            effectiveBinaryOverrides = listOf(KonanProperties.parse(binaryArguments + release)),
        )

        assertThat(compilationOverrides).containsExactly(
            entry("commonOnly", "common"),
            entry("overridden", "compilation"),
            entry("targetOnly", "target"),
            entry("compilationOnly", "compilation"),
        )
        assertThat(nativeLinkOverrides).containsExactly(
            entry("commonOnly", "common"),
            entry("overridden", "nativeLink"),
            entry("targetOnly", "target"),
            entry("compilationOnly", "compilation"),
            entry("nativeLinkOnly", "nativeLink"),
        )
        assertThat(debugOverrides).containsExactly(
            entry("commonOnly", "common"),
            entry("overridden", "debug"),
            entry("targetOnly", "target"),
            entry("compilationOnly", "compilation"),
            entry("nativeLinkOnly", "nativeLink"),
            entry("debugOnly", "debug"),
        )
        assertThat(debugOverrides).doesNotContainKey("releaseOnly")
        assertThat(releaseOverrides).containsExactly(
            entry("commonOnly", "common"),
            entry("overridden", "release"),
            entry("targetOnly", "target"),
            entry("compilationOnly", "compilation"),
            entry("nativeLinkOnly", "nativeLink"),
            entry("releaseOnly", "release"),
        )
        assertThat(releaseOverrides).doesNotContainKey("debugOnly")
    }

    @Test
    fun `uses identical effective binary overrides`() {
        val binaryOverrides = mapOf("key" to "binary")

        val overrides = KonanProperties.select(
            effectiveCompilationOverrides = mapOf("key" to "compilation"),
            effectiveBinaryOverrides = listOf(binaryOverrides, binaryOverrides),
        )

        assertThat(overrides).isEqualTo(binaryOverrides)
    }

    @Test
    fun `uses an empty effective binary map instead of compilation overrides`() {
        val overrides = KonanProperties.select(
            effectiveCompilationOverrides = mapOf("key" to "compilation"),
            effectiveBinaryOverrides = listOf(emptyMap()),
        )

        assertThat(overrides).isEmpty()
    }

    @Test
    fun `rejects conflicting effective binary overrides`() {
        assertThatThrownBy {
            KonanProperties.select(
                effectiveCompilationOverrides = emptyMap(),
                effectiveBinaryOverrides = listOf(
                    mapOf("key" to "first"),
                    mapOf("key" to "second"),
                ),
            )
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("must use identical -Xoverride-konan-properties values")
    }

    @Test
    fun `parses attached and separate override arguments`() {
        val overrides = KonanProperties.parse(
            listOf(
                "-Xunrelated-option",
                "-Xoverride-konan-properties=targetSysRoot.linux_x64=/custom/sysroot;dependencies.macos_arm64-linux_x64=",
                "-Xoverride-konan-properties",
                "gccToolchain.linux_x64=/custom/gcc",
            ),
        )

        assertThat(overrides).containsExactly(
            entry("targetSysRoot.linux_x64", "/custom/sysroot"),
            entry("dependencies.macos_arm64-linux_x64", ""),
            entry("gccToolchain.linux_x64", "/custom/gcc"),
        )
    }

    @Test
    fun `preserves equals signs in values and lets later entries win`() {
        val overrides = KonanProperties.parse(
            listOf(
                "-Xoverride-konan-properties=key=first;url=https://example.test?a=b",
                "-Xoverride-konan-properties=key=second",
            ),
        )

        assertThat(overrides).containsEntry("key", "second")
        assertThat(overrides).containsEntry("url", "https://example.test?a=b")
    }

    @Test
    fun `preserves commas and whitespace in raw values`() {
        val overrides = KonanProperties.parse(
            listOf("-Xoverride-konan-properties=comma=a,b;whitespace=  a b  "),
        )

        assertThat(overrides).containsExactly(
            entry("comma", "a,b"),
            entry("whitespace", "  a b  "),
        )
    }

    @Test
    fun `lets later entries in one payload win`() {
        val overrides = KonanProperties.parse(
            listOf("-Xoverride-konan-properties=key=first;key=second"),
        )

        assertThat(overrides).containsExactly(entry("key", "second"))
    }

    @Test
    fun `rejects an empty override payload`() {
        assertThatThrownBy {
            KonanProperties.parse(listOf("-Xoverride-konan-properties="))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("entry ''; expected key=value")
    }

    @Test
    fun `rejects empty entries in an override payload`() {
        listOf(
            "-Xoverride-konan-properties=;key=value",
            "-Xoverride-konan-properties=key=value;",
            "-Xoverride-konan-properties=key=value;;other=value",
        ).forEach { argument ->
            assertThatThrownBy {
                KonanProperties.parse(listOf(argument))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("entry ''; expected key=value")
        }
    }

    @Test
    fun `rejects a missing separate payload`() {
        assertThatThrownBy {
            KonanProperties.parse(listOf("-Xoverride-konan-properties"))
        }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires a semicolon-delimited key=value argument")
    }

    @Test
    fun `rejects entries with a missing key or separator`() {
        listOf(
            "-Xoverride-konan-properties==missing-key",
            "-Xoverride-konan-properties=missing-separator",
        ).forEach { argument ->
            assertThatThrownBy {
                KonanProperties.parse(listOf(argument))
            }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("expected key=value")
        }
    }
}
