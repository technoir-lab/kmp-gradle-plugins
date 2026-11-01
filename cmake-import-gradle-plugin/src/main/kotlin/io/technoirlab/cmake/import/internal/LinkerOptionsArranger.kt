package io.technoirlab.cmake.import.internal

import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * Arranges C-interop linker options for Kotlin/Native autolinking on Apple targets.
 *
 * Kotlin/Native embeds a library's linker options as autolink entries in Apple static frameworks and Swift export static libraries,
 * so that apps linking those binaries also link the libraries they depend on. It embeds only `-l<name>` and `-framework <name>`
 * options, and only up to the first other option. Apple's linker ignores weak frameworks and libraries in autolink entries.
 */
internal class LinkerOptionsArranger {
    /**
     * Returns [linkerOptions] arranged for [target].
     *
     * For Apple targets, options that Kotlin/Native can embed come first, with a strong copy of each weak framework or library in
     * place of the weak option. All other options follow in their original order, the weak ones included, so binaries that
     * Kotlin/Native links itself still link those frameworks and libraries weakly. An app that autolinks a strong copy still links
     * it weakly when all its references to it are weak, as references to APIs newer than the library's deployment target are.
     *
     * Other targets keep the original order because their linkers interpret options by position.
     */
    fun arrange(linkerOptions: List<String>, target: KonanTarget): List<String> {
        if (!target.family.isAppleFamily) return linkerOptions

        val autolinkable = mutableListOf<String>()
        val others = mutableListOf<String>()
        val options = linkerOptions.iterator()
        while (options.hasNext()) {
            val option = options.next()
            when {
                option == FRAMEWORK_OPTION && options.hasNext() -> autolinkable += listOf(option, options.next())
                option == WEAK_FRAMEWORK_OPTION && options.hasNext() -> {
                    val framework = options.next()
                    autolinkable += listOf(FRAMEWORK_OPTION, framework)
                    others += listOf(option, framework)
                }
                option.isWeakLibraryOption() -> {
                    autolinkable += LIBRARY_OPTION_PREFIX + option.removePrefix(WEAK_LIBRARY_OPTION_PREFIX)
                    others += option
                }
                option.isLibraryOption() -> autolinkable += option
                else -> others += option
            }
        }
        return autolinkable + others
    }

    private fun String.isLibraryOption(): Boolean =
        hasValueAfter(LIBRARY_OPTION_PREFIX) && this !in NON_LIBRARY_OPTIONS && !startsWith(LAZY_LIBRARY_OPTION_PREFIX)

    private fun String.isWeakLibraryOption(): Boolean = hasValueAfter(WEAK_LIBRARY_OPTION_PREFIX)

    private fun String.hasValueAfter(prefix: String): Boolean = length > prefix.length && startsWith(prefix)

    private companion object {
        const val FRAMEWORK_OPTION = "-framework"
        const val WEAK_FRAMEWORK_OPTION = "-weak_framework"
        const val LIBRARY_OPTION_PREFIX = "-l"
        const val WEAK_LIBRARY_OPTION_PREFIX = "-weak-l"
        const val LAZY_LIBRARY_OPTION_PREFIX = "-lazy-l"

        /**
         * Apple linker options that start with `-l` but do not name a library, besides `-lazy-l<name>`.
         */
        val NON_LIBRARY_OPTIONS = setOf(
            "-lazy_framework",
            "-lazy_library",
            "-ld_classic",
            "-ld_new",
            "-ld_prime",
            "-load_hidden",
            "-lto_library",
        )
    }
}
