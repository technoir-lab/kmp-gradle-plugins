package io.technoirlab.cmake.import.internal

/**
 * Compiler-driver options that choose how the driver links the runtime libraries it adds to a link.
 *
 * @property option The option as the compiler driver takes it.
 * @property isCxxOnly Whether the option applies only to C++ links.
 */
internal enum class CompilerDriverRuntimeOption(
    val option: String,
    val isCxxOnly: Boolean = false,
) {
    STATIC_LIBGCC("-static-libgcc"),
    SHARED_LIBGCC("-shared-libgcc"),
    STATIC_LIBSTDCXX("-static-libstdc++", isCxxOnly = true),
    ;

    companion object {
        /**
         * Returns the runtime option written as [option], or null for any other option.
         */
        fun of(option: String): CompilerDriverRuntimeOption? = entries.find { it.option == option }
    }
}
