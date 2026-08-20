package io.technoirlab.cmake.import.internal

import java.nio.file.Path

/**
 * Values written to a Kotlin/Native C-interop definition for an installed CMake target.
 */
internal data class CInteropDefinition(
    val packageName: String,
    val headers: List<Path>,
    val includeDirectory: Path,
    val archive: Path,
    val linkerOptions: List<String> = emptyList(),
)
