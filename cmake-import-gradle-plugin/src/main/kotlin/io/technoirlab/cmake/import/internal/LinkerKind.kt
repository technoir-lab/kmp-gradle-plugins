package io.technoirlab.cmake.import.internal

import org.jetbrains.kotlin.konan.target.LinkerFlags

/**
 * How Kotlin/Native links binaries for a target, which decides the form its linker options take.
 */
internal enum class LinkerKind {
    /**
     * Kotlin/Native links through the Clang driver, which takes compiler-driver options.
     */
    COMPILER_DRIVER,

    /**
     * Kotlin/Native runs Apple's linker.
     */
    APPLE,

    /**
     * Kotlin/Native runs a linker that takes GNU-style options, such as LLD.
     */
    GNU,
    ;

    companion object {
        /**
         * Returns the kind of [linker].
         */
        fun of(linker: LinkerFlags): LinkerKind = when {
            linker.useCompilerDriverAsLinker -> COMPILER_DRIVER
            linker.configurables.target.family.isAppleFamily -> APPLE
            else -> GNU
        }
    }
}
