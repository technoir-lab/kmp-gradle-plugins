package io.technoirlab.cmake.import.internal

internal class PkgConfigParser {
    fun parse(content: String, predefinedVariables: Map<String, String> = emptyMap()): PkgConfigMetadata? {
        val variables = predefinedVariables.toMutableMap()
        val fields = mutableMapOf<String, String>()
        for (line in content.lineSequence()) {
            if (line.isBlank() || line.startsWith('#')) continue
            if (line.endsWith('\\')) return null

            val variableMatch = VARIABLE.matchEntire(line)
            if (variableMatch != null) {
                val (name, value) = variableMatch.destructured
                if (variables.put(name, value) != null) return null
                continue
            }

            val fieldMatch = FIELD.matchEntire(line) ?: return null
            val (name, value) = fieldMatch.destructured
            if (fields.put(name, value.trimStart()) != null) return null
        }
        return PkgConfigMetadata(variables, fields)
    }

    private companion object {
        val VARIABLE = Regex("([A-Za-z_][A-Za-z0-9_]*)=(.*)")
        val FIELD = Regex("([A-Za-z][A-Za-z0-9.]*):[ \\t]*(.*)")
    }
}
