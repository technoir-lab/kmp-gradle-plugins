package io.technoirlab.cmake.import.internal

import java.nio.file.Path

internal fun Path.relativePathString(base: Path): String = base.toAbsolutePath()
    .normalize()
    .relativize(toAbsolutePath().normalize())
    .portablePathString()

internal fun Path.normalizedPathString(): String = toAbsolutePath().normalize().portablePathString()

internal fun Path.portablePathString(): String = toString().replace('\\', '/')
