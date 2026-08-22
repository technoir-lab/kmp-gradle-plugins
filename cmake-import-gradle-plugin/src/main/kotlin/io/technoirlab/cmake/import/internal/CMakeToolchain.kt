package io.technoirlab.cmake.import.internal

import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * The Kotlin/Native compiler settings needed by a CMake toolchain.
 */
internal data class CMakeToolchain(
    val target: KonanTarget,
    val targetTriple: String,
    val systemName: String,
    val processor: String,
    val sysroot: String,
    val findRoots: List<String>,
    val cCompiler: CMakeCompilerSettings,
    val cxxCompiler: CMakeCompilerSettings,
    val archiver: String,
    val compilerDriverLinker: String? = null,
    val androidExecutableLinker: CMakeExecutableLinker? = null,
    val appleDeploymentTarget: String? = null,
    val appleSdkVersion: String? = null,
    val androidApi: String? = null,
    val androidAbi: String? = null,
)

internal data class CMakeCompilerSettings(
    val command: String,
    val arguments: List<String>,
)

internal data class CMakeExecutableLinker(
    val compilerDriver: String,
    val libraries: List<String>,
)
