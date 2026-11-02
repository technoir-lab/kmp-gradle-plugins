package io.technoirlab.cmake.import.api

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configuration for a CMake target used by the Kotlin Multiplatform CMake integration.
 */
@CMakeImportDsl
interface CMakeImportExtension {
    /**
     * The directory containing the CMake project and its `CMakeLists.txt`.
     */
    val sourceDirectory: DirectoryProperty

    /**
     * CMake target to configure and build.
     */
    val targetName: Property<String>

    /**
     * CMake configuration, for example `Debug` or `Release`.
     */
    val buildType: Property<String>

    /**
     * Optional CMake install component. When absent, the full project install is staged.
     */
    val installComponent: Property<String>

    /**
     * The package name for the generated bindings.
     */
    val packageName: Property<String>

    /**
     * Public headers to expose to Kotlin/Native, as paths relative to the installed `include/` directory.
     * C-interop includes them in this order, so list a header before any header that uses it without including it.
     * When empty, every installed public header is exposed, in path order.
     */
    val headers: ListProperty<String>

    /**
     * Cache definitions passed to CMake as `-D<name>=<value>` arguments during configuration.
     */
    val defines: MapProperty<String, String>

    companion object {
        const val NAME = "cmakeImport"
    }
}
