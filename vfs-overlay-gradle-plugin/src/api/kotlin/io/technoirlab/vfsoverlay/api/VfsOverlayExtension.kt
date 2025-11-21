package io.technoirlab.vfsoverlay.api

import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import java.io.File

/**
 * Configure VFS overlay for Kotlin/Native cinterop compilations.
 *
 * Example usage:
 * ```kotlin
 * vfsOverlay {
 *     mapping(File("/original/path"), File("/virtual/path"))
 * }
 * ```
 */
@VfsOverlayDsl
interface VfsOverlayExtension {
    /**
     * Map of file mappings from source (original) files to target (virtual) files.
     */
    val mappings: MapProperty<File, File>

    /**
     * Path to the Kotlin/Native dependencies directory.
     */
    val kotlinNativeDependenciesDir: Provider<File>

    /**
     * Adds a file mapping from a source (original) path to a target (virtual) path.
     */
    fun mapping(source: File, target: File)

    /**
     * Adds a file mapping from a source (original) path to a target (virtual) path.
     */
    fun mapping(source: Provider<File>, target: Provider<File>)
}
