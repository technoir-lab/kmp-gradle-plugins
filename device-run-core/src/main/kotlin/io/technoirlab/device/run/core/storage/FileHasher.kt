package io.technoirlab.device.run.core.storage

import java.nio.file.Path

/**
 * Computes a hash of the content of a file.
 */
internal fun interface FileHasher {
    fun hash(file: Path): String
}
