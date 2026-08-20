package io.technoirlab.cmake.import.internal

import java.nio.file.Path

/**
 * Writes a Kotlin/Native C-interop definition for an installed CMake target.
 */
internal class CInteropDefinitionGenerator {
    fun generate(packageName: String, headers: List<Path>, includeDirectory: Path, archive: Path): String {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        return buildString {
            appendLine("package = ${packageName.definitionValue()}")
            if (headers.isNotEmpty()) {
                appendLine(
                    "headers = ${headers.joinToString(" ") { it.relativePathString(includeDirectory).definitionValue() }}",
                )
            }
            appendLine("compilerOpts = ${"-I${includeDirectory.normalizedPathString()}".definitionValue()}")
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
