package io.technoirlab.cmake.import.internal

import java.nio.file.Path

internal fun Path.relativePath(base: Path): Path = base.toAbsolutePath()
    .normalize()
    .relativize(toAbsolutePath().normalize())

internal fun Path.normalizedPathString(): String = toAbsolutePath().normalize().portablePathString()

internal fun Path.portablePathString(): String = toString().replace('\\', '/')
