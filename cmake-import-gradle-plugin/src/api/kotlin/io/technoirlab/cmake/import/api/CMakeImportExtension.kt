package io.technoirlab.cmake.import.api

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty

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
     * When empty, every installed public header is exposed.
     */
    val headers: SetProperty<String>

    /**
     * Cache definitions passed to CMake as `-D<name>=<value>` arguments during configuration.
     */
    val defines: MapProperty<String, String>

    companion object {
        const val NAME = "cmakeImport"
    }
}
