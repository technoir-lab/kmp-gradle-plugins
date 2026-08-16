package io.technoirlab.cmake.import.internal

import java.nio.file.Path

internal data class CMakeInstallOutput(
    val includeDirectory: Path,
    val libraryDirectory: Path,
    val headers: List<Path>,
    val archives: List<Path>,
)
