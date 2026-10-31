package io.technoirlab.cmake.import.pkconfig

import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Template
import io.technoirlab.cmake.import.pkconfig.PkgConfigValuePart.Verbatim

/**
 * Splits expanded pkg-config text into arguments as `pkgconf_argv_split` does.
 *
 * Template text is split at whitespace, honoring single quotes, double quotes, and backslash escapes. Verbatim text is appended to the
 * current argument as written. Empty arguments are dropped because they carry no option.
 */
internal class ArgumentSplitter {
    /**
     * Returns the arguments of [parts], or `null` when the text ends within a quote or after an escaping backslash.
     */
    fun split(parts: List<PkgConfigValuePart>): List<String>? {
        val splitting = Splitting()
        parts.forEach(splitting::accept)
        return splitting.finish()
    }

    /**
     * State of splitting one text.
     */
    private class Splitting {
        private val arguments = mutableListOf<String>()
        private val argument = StringBuilder()
        private var quote: Char? = null
        private var isEscaped = false

        fun accept(part: PkgConfigValuePart) {
            when (part) {
                is Template -> part.text.forEach(::acceptTemplateCharacter)
                is Verbatim -> part.text.forEach(::acceptVerbatimCharacter)
            }
        }

        fun finish(): List<String>? {
            if (isEscaped || quote != null) return null
            endArgument()
            return arguments
        }

        private fun acceptTemplateCharacter(character: Char) {
            when {
                isEscaped -> acceptEscapedCharacter(character)
                quote != null -> acceptQuotedCharacter(character)
                character in ARGUMENT_SEPARATORS -> endArgument()
                character == '\\' -> isEscaped = true
                character == '"' || character == '\'' -> quote = character
                else -> argument.append(character)
            }
        }

        private fun acceptVerbatimCharacter(character: Char) {
            if (isEscaped) {
                acceptEscapedCharacter(character)
            } else {
                argument.append(character)
            }
        }

        private fun acceptQuotedCharacter(character: Char) {
            when {
                character == quote -> quote = null
                character == '\\' && quote == '"' -> isEscaped = true
                else -> argument.append(character)
            }
        }

        private fun acceptEscapedCharacter(character: Char) {
            // Within double quotes, a backslash escapes only a backslash or a double quote.
            if (quote == '"' && character != '\\' && character != '"') {
                argument.append('\\')
            }
            argument.append(character)
            isEscaped = false
        }

        private fun endArgument() {
            if (argument.isNotEmpty()) {
                arguments += argument.toString()
                argument.clear()
            }
        }
    }

    private companion object {
        const val ARGUMENT_SEPARATORS = " \t\n\u000B\u000C\r"
    }
}
