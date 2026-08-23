package io.technoirlab.cmake.import.internal

import org.jetbrains.kotlin.konan.file.use
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.bufferedReader

internal object KonanProperties {
    const val KONAN_PROPERTIES_PATH = "konan/konan.properties"

    private const val OPTION = "-Xoverride-konan-properties"

    fun load(file: Path, overrides: Map<String, String>): Properties = file.bufferedReader().use { reader ->
        Properties().apply {
            load(reader)
            overrides.forEach(::setProperty)
        }
    }

    fun parse(arguments: List<String>): Map<String, String> {
        val overrides = linkedMapOf<String, String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            val payload = when {
                argument == OPTION -> {
                    index += 1
                    require(index < arguments.size) {
                        "$OPTION requires a semicolon-delimited key=value argument"
                    }
                    arguments[index]
                }
                argument.startsWith("$OPTION=") -> argument.substringAfter('=')
                else -> null
            }

            payload?.split(';')?.forEach { entry ->
                val separator = entry.indexOf('=')
                require(separator > 0) {
                    "Invalid $OPTION entry '$entry'; expected key=value"
                }
                overrides[entry.substring(0, separator)] = entry.substring(separator + 1)
            }
            index += 1
        }
        return overrides
    }

    /**
     * Selects overrides parsed from full effective compiler argument lists.
     *
     * Each binary map already includes its inherited common, target, compilation, and link-only arguments.
     */
    fun select(
        effectiveCompilationOverrides: Map<String, String>,
        effectiveBinaryOverrides: List<Map<String, String>>,
    ): Map<String, String> {
        val distinctBinaryOverrides = effectiveBinaryOverrides.distinct()
        require(distinctBinaryOverrides.size <= 1) {
            "Kotlin/Native binaries must use identical $OPTION values because they share one generated CMake toolchain"
        }
        return distinctBinaryOverrides.singleOrNull() ?: effectiveCompilationOverrides
    }
}
