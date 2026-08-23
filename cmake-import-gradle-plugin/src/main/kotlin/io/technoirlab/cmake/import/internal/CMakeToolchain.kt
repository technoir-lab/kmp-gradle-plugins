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
    val cCompiler: CMakeCompilerDriverSettings,
    val cxxCompiler: CMakeCompilerDriverSettings,
    val archiver: String,
    val compilerDriverLinker: String? = null,
    val androidExecutableLinker: CMakeExecutableLinker? = null,
    val appleDeploymentTarget: String? = null,
    val appleSdkVersion: String? = null,
    val androidApi: String? = null,
    val androidAbi: String? = null,
)

internal data class CMakeCompilerDriverSettings(
    val command: String,
    val arguments: List<String>,
    val linkArguments: List<String> = emptyList(),
)

internal data class CMakeExecutableLinker(
    val compilerDriver: String,
    val libraries: List<String>,
)
