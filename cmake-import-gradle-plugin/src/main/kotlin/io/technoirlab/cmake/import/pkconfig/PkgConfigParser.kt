package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.internal.normalizedPathString
import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Template
import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Verbatim
import java.nio.file.Path
import kotlin.io.path.name

internal class PkgConfigParser {
    /**
     * Parses the [content] of a pkg-config file located in [directory], which defines the `pcfiledir` variable.
     *
     * Like `pkg-config --define-prefix`, a file in `<prefix>/lib/pkgconfig` or `<prefix>/share/pkgconfig` gets `<prefix>` as its
     * `prefix` variable, and later variables that start with the original prefix move along with it.
     */
    fun parse(content: String, directory: Path): PkgConfigMetadata? {
        val prefixRelocation = directory.definedPrefix()?.let(::PrefixRelocation)
        val variables = mutableMapOf<String, PkgConfigValuePart>(
            PC_FILE_DIR_VARIABLE to Verbatim(directory.normalizedPathString()),
        )
        val fields = mutableMapOf<String, String>()
        for (line in content.lineSequence()) {
            if (line.isBlank() || line.startsWith('#')) continue
            if (line.endsWith('\\')) return null

            val variableMatch = VARIABLE.matchEntire(line)
            if (variableMatch != null) {
                val (name, value) = variableMatch.destructured
                val part = prefixRelocation?.relocate(name, value) ?: Template(value)
                if (variables.put(name, part) != null) return null
                continue
            }

            val fieldMatch = FIELD.matchEntire(line) ?: return null
            val (name, value) = fieldMatch.destructured
            if (fields.put(name, value.trimStart()) != null) return null
        }
        return PkgConfigMetadata(variables, fields)
    }

    private fun Path.definedPrefix(): String? {
        if (!name.equals(PKG_CONFIG_DIRECTORY_NAME, ignoreCase = true)) return null
        val prefixSubdirectory = parent ?: return null
        if (PREFIX_SUBDIRECTORY_NAMES.none { prefixSubdirectory.name.equals(it, ignoreCase = true) }) return null
        return prefixSubdirectory.parent?.normalizedPathString()
    }

    /**
     * Redefines the `prefix` variable as [prefix] and makes later variables under the original prefix reference it.
     */
    private class PrefixRelocation(
        private val prefix: String,
    ) {
        private var originalPrefix = ""

        fun relocate(name: String, value: String): PkgConfigValuePart {
            if (name == PREFIX_VARIABLE) {
                originalPrefix = value.trim().trimEnd('/')
                return Verbatim(prefix)
            }
            val trimmedValue = value.trim()
            val subpath = trimmedValue.removePrefix(originalPrefix).takeIf { it != trimmedValue }
            return if (subpath != null && (subpath.isEmpty() || subpath.startsWith('/'))) {
                Template("\${$PREFIX_VARIABLE}$subpath")
            } else {
                Template(value)
            }
        }
    }

    private companion object {
        const val PC_FILE_DIR_VARIABLE = "pcfiledir"
        const val PREFIX_VARIABLE = "prefix"
        const val PKG_CONFIG_DIRECTORY_NAME = "pkgconfig"
        val PREFIX_SUBDIRECTORY_NAMES = listOf("lib", "share")
        val VARIABLE = Regex("([A-Za-z_][A-Za-z0-9_]*)=(.*)")
        val FIELD = Regex("([A-Za-z][A-Za-z0-9.]*):[ \\t]*(.*)")
    }
}
