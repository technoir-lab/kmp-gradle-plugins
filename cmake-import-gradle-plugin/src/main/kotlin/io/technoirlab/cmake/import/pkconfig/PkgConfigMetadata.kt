package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Template
import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Verbatim

internal data class PkgConfigMetadata(
    private val variables: Map<String, PkgConfigValuePart>,
    private val fields: Map<String, String>,
) {
    private val argumentSplitter = ArgumentSplitter()

    fun field(name: String): String? = fields[name]

    /**
     * Expands the variable [name] into a single argument.
     *
     * Returns `null` when the variable is undefined, cannot be expanded, or does not form exactly one argument.
     */
    fun expandedVariable(name: String): String? = expandVariable(name, emptySet())
        ?.let(argumentSplitter::split)
        ?.singleOrNull()

    /**
     * Expands the field [name] and splits it into arguments, honoring quotes and backslash escapes as pkgconf does.
     *
     * Returns an empty list when the field is absent, or `null` when it cannot be expanded or ends within a quote or escape.
     */
    fun expandedFieldTokens(name: String): List<String>? {
        val value = fields[name] ?: return emptyList()
        return expandTemplate(value, emptySet())?.let(argumentSplitter::split)
    }

    private fun expandVariable(name: String, expanding: Set<String>): List<PkgConfigValuePart>? {
        if (name in expanding) return null
        return when (val value = variables[name]) {
            is Template -> expandTemplate(value.text, expanding + name)
            is Verbatim -> listOf(value)
            null -> null
        }
    }

    private fun expandTemplate(text: String, expanding: Set<String>): List<PkgConfigValuePart>? {
        val expanded = mutableListOf<PkgConfigValuePart>()
        var start = 0
        for (reference in VARIABLE_REFERENCE.findAll(text)) {
            expanded += Template(text.substring(start, reference.range.first))
            expanded += expandVariable(reference.groupValues[1], expanding) ?: return null
            start = reference.range.last + 1
        }
        expanded += Template(text.substring(start))
        return expanded
    }

    private companion object {
        val VARIABLE_REFERENCE = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
    }
}
