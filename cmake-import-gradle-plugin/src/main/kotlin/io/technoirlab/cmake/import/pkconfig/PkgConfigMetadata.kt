package io.technoirlab.cmake.import.pkconfig

internal data class PkgConfigMetadata(
    private val variables: Map<String, String>,
    private val fields: Map<String, String>,
) {
    fun field(name: String): String? = fields[name]

    fun expandedVariable(name: String): String? = expandVariable(name, emptySet())

    fun expandedFieldTokens(name: String): List<String>? {
        val value = fields[name] ?: return emptyList()
        val expanded = expand(value, emptySet()) ?: return null
        if (expanded.any { it == '\'' || it == '"' || it == '\\' }) return null
        return expanded.trim()
            .takeIf { it.isNotEmpty() }
            ?.split(WHITESPACE)
            .orEmpty()
    }

    private fun expand(value: String, expanding: Set<String>): String? {
        var expanded = value
        while (true) {
            val reference = VARIABLE_REFERENCE.find(expanded) ?: return expanded
            val name = reference.groupValues[1]
            val replacement = expandVariable(name, expanding) ?: return null
            expanded = expanded.replaceRange(reference.range, replacement)
        }
    }

    private fun expandVariable(name: String, expanding: Set<String>): String? {
        if (name in expanding) return null
        val value = variables[name] ?: return null
        return expand(value, expanding + name)
    }

    private companion object {
        val VARIABLE_REFERENCE = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}")
        val WHITESPACE = Regex("\\s+")
    }
}
