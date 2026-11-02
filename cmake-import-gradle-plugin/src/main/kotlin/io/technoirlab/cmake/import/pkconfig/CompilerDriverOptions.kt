package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.internal.CompilerDriverRuntimeOption
import io.technoirlab.cmake.import.internal.LinkerKind

/**
 * Compiler-driver options, with the linker arguments of their `-Wl,` and `-Xlinker` options unwrapped.
 *
 * Options can be removed by the linker arguments they pass, whether or not they are wrapped, and written in the form that the linker
 * of a target takes. A `-Wl,` option with an empty argument and a `-Xlinker` option without one stay single compiler-driver options.
 */
internal class CompilerDriverOptions private constructor(
    private val arguments: List<Argument>,
) {
    /**
     * Returns whether [optionLength] matches any of these options, as [without] would remove it.
     */
    fun contains(optionLength: (arguments: List<String>, index: Int) -> Int?): Boolean =
        without(optionLength).arguments.size < arguments.size

    /**
     * Returns these options without the ones that [optionLength] matches.
     *
     * [optionLength] receives the compiler-driver arguments and the unwrapped linker arguments as separate lists. It returns how many
     * arguments from the given index on form an option to remove, or null to keep the argument at that index. A `-Xlinker` option is
     * removed with its argument, and a `-Wl,` option keeps the arguments that remain.
     */
    fun without(optionLength: (arguments: List<String>, index: Int) -> Int?): CompilerDriverOptions {
        val removedPositions = mutableSetOf<Int>()
        val (compilerDriverPositions, linkerPositions) = arguments.indices.partition { arguments[it].form == Form.COMPILER_DRIVER }
        for (positions in listOf(compilerDriverPositions, linkerPositions)) {
            val values = positions.map { arguments[it].value }
            var index = 0
            while (index < values.size) {
                val length = optionLength(values, index)
                if (length != null) {
                    removedPositions += positions.subList(index, index + length)
                }
                index += length ?: 1
            }
        }
        return CompilerDriverOptions(arguments.filterIndexed { position, _ -> position !in removedPositions })
    }

    /**
     * Returns these options in the form that [linkerKind] takes.
     *
     * The compiler driver takes the options as written. For a linker, `-Wl,` and `-Xlinker` options become the linker arguments
     * they pass, `-pthread` becomes `-lpthread`, `-rdynamic` becomes the linker's option that exports dynamic symbols, and
     * [CompilerDriverRuntimeOption]s are dropped, because Kotlin/Native links its runtime libraries itself. Other options are
     * unchanged.
     *
     * @throws IllegalArgumentException if a linker would get a `-Wl,` option with an empty argument or a `-Xlinker` option without
     * one; the message names [source] as the place that lists the options.
     */
    fun toOptions(linkerKind: LinkerKind, source: String): List<String> = when (linkerKind) {
        LinkerKind.COMPILER_DRIVER -> toCompilerDriverOptions()
        LinkerKind.APPLE, LinkerKind.GNU -> arguments.flatMap { argument ->
            when (argument.form) {
                Form.COMPILER_DRIVER -> argument.value.toLinkerOptions(linkerKind, source)
                Form.WL, Form.XLINKER -> listOf(argument.value)
            }
        }
    }

    private fun toCompilerDriverOptions(): List<String> = arguments
        .groupBy { it.option }
        .values
        .flatMap { optionArguments ->
            when (optionArguments.first().form) {
                Form.COMPILER_DRIVER -> optionArguments.map { it.value }
                Form.WL -> listOf(WL_PREFIX + optionArguments.joinToString(",") { it.value })
                Form.XLINKER -> optionArguments.flatMap { listOf(XLINKER_OPTION, it.value) }
            }
        }

    private fun String.toLinkerOptions(linkerKind: LinkerKind, source: String): List<String> = when {
        this == "-pthread" -> listOf("-lpthread")
        this == "-rdynamic" -> listOf(if (linkerKind == LinkerKind.APPLE) "-export_dynamic" else "--export-dynamic")
        CompilerDriverRuntimeOption.of(this) != null -> emptyList()
        this == XLINKER_OPTION -> throw IllegalArgumentException(malformedOptionMessage(source, "linker argument is missing"))
        startsWith(WL_PREFIX) -> throw IllegalArgumentException(malformedOptionMessage(source, "linker arguments must not be empty"))
        else -> listOf(this)
    }

    private fun String.malformedOptionMessage(source: String, reason: String): String =
        "Malformed compiler-driver linker option '$this' in $source: $reason"

    /**
     * An argument of the option at index [option] of the parsed options, which passes it in [form].
     */
    private data class Argument(
        val value: String,
        val form: Form,
        val option: Int,
    )

    private enum class Form {
        /**
         * The compiler driver takes the argument.
         */
        COMPILER_DRIVER,

        /**
         * A `-Wl,` option passes the argument to the linker, among the comma-separated ones.
         */
        WL,

        /**
         * A `-Xlinker` option passes the argument to the linker.
         */
        XLINKER,
    }

    companion object {
        private const val WL_PREFIX = "-Wl,"
        private const val XLINKER_OPTION = "-Xlinker"

        /**
         * Returns [options] with the linker arguments of their `-Wl,` and `-Xlinker` options unwrapped.
         */
        fun parse(options: List<String>): CompilerDriverOptions {
            val arguments = mutableListOf<Argument>()
            var index = 0
            while (index < options.size) {
                val option = options[index]
                val wrappedArguments = option.removePrefix(WL_PREFIX).split(',')
                when {
                    option == XLINKER_OPTION && index + 1 < options.size -> {
                        arguments += Argument(options[index + 1], Form.XLINKER, index)
                        index++
                    }
                    option.startsWith(WL_PREFIX) && wrappedArguments.none { it.isEmpty() } ->
                        wrappedArguments.mapTo(arguments) { Argument(it, Form.WL, index) }
                    else -> arguments += Argument(option, Form.COMPILER_DRIVER, index)
                }
                index++
            }
            return CompilerDriverOptions(arguments)
        }
    }
}
