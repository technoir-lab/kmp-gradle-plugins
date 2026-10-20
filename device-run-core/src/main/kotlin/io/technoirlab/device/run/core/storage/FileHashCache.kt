package io.technoirlab.device.run.core.storage

import com.google.common.hash.Hashing
import com.google.common.io.MoreFiles.asByteSource
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.getLastModifiedTime

internal class FileHashCache(
    private val fileHasher: FileHasher = FileHasher { file -> asByteSource(file).hash(Hashing.sha256()).toString() },
) {
    // Each hash is computed once by its lazy value, so hashing a large file does not block access to other entries.
    private val hashes = ConcurrentHashMap<FileVersion, Lazy<String>>()

    fun retrieveOrGenerateHash(file: Path): String {
        val version = FileVersion(file.toAbsolutePath(), file.getLastModifiedTime())
        return hashes.computeIfAbsent(version) { lazy { fileHasher.hash(file) } }.value
    }

    private data class FileVersion(
        val path: Path,
        val lastModified: FileTime,
    )
}
