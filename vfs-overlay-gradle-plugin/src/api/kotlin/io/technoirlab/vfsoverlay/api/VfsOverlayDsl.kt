package io.technoirlab.vfsoverlay.api

/**
 * Marks VFS Overlay plugin DSL.
 */
@DslMarker
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
internal annotation class VfsOverlayDsl
