package io.technoirlab.cmake.import.internal

import java.nio.file.Path

/**
 * Values written to a Kotlin/Native C-interop definition.
 */
internal data class CInteropDefinition(
    val packageName: String,
    val headers: List<Path>, // relative to [includeDirectory]
    val headerFilter: List<Path>, // relative to [includeDirectory]
    val includeDirectory: Path,
    val archive: Path,
    val linkerOptions: List<String> = emptyList(),
)
