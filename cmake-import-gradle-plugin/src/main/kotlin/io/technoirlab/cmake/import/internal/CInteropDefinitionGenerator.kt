package io.technoirlab.cmake.import.internal

/**
 * Writes a Kotlin/Native C-interop definition file.
 */
internal class CInteropDefinitionGenerator {
    fun generate(definition: CInteropDefinition): String = with(definition) {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        buildString {
            appendLine("package = ${packageName.definitionValue()}")
            if (headers.isNotEmpty()) {
                val headerValues = headers.map { it.portablePathString() }.sorted()
                appendLine(
                    "headers = ${headerValues.joinToString(" ") { it.definitionValue() }}",
                )
            }
            if (headerFilter.isNotEmpty()) {
                val headerFilterValues = headerFilter
                    .map { it.portablePathString() }
                    .distinct()
                    .sorted()
                appendLine(
                    "headerFilter = ${headerFilterValues.joinToString(" ") { it.definitionValue() }}",
                )
            }
            appendLine("compilerOpts = ${"-I${includeDirectory.normalizedPathString()}".definitionValue()}")
            if (linkerOptions.isNotEmpty()) {
                appendLine("linkerOpts = ${linkerOptions.joinToString(" ") { it.definitionValue() }}")
            }
            appendLine("staticLibraries = ${archive.fileName.toString().definitionValue()}")
            appendLine("libraryPaths = ${archive.parent.normalizedPathString().definitionValue()}")
        }
    }

    private fun String.definitionValue(): String {
        if (isNotEmpty() && all { !it.isWhitespace() && it != '#' && it != '"' }) {
            return this
        }
        return "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}
