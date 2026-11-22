package io.technoirlab.vfsoverlay.api

import org.gradle.api.file.Directory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import java.io.File

/**
 * Configure VFS overlay for Kotlin/Native cinterop compilations.
 *
 * Example usage:
 * ```kotlin
 * vfsOverlay {
 *     mapping(layout.projectDirectory.dir("original"), layout.projectDirectory.dir("virtual"))
 * }
 * ```
 */
@VfsOverlayDsl
interface VfsOverlayExtension {
    /**
     * Map of file mappings from source (original) files to target (virtual) files.
     * Relative paths are resolved against the project directory.
     */
    val mappings: MapProperty<File, File>

    /**
     * Path to the Kotlin/Native dependencies directory.
     */
    val kotlinNativeDependenciesDir: Provider<Directory>

    /**
     * Adds a file mapping from a source (original) path to a target (virtual) path.
     * Relative paths are resolved against the project directory.
     */
    fun mapping(source: File, target: File)

    /**
     * Adds a directory mapping from a source (original) directory to a target (virtual) directory.
     */
    fun mapping(source: Directory, target: Directory)

    /**
     * Adds a file mapping from a source (original) path to a target (virtual) path.
     * Relative paths are resolved against the project directory.
     * Has no effect if the source or target provider is empty.
     */
    fun mapping(source: Provider<File>, target: Provider<File>)
}
