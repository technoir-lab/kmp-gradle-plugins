package io.technoirlab.cmake.import.pkconfig

/**
 * Part of a pkg-config variable value or field.
 */
internal sealed interface PkgConfigValuePart {
    val text: String

    /**
     * Text from the pkg-config file, which can reference variables and quote or escape characters.
     */
    data class Template(
        override val text: String,
    ) : PkgConfigValuePart

    /**
     * Text that pkg-config defines itself, such as the directory of the file, which is neither split nor unquoted.
     */
    data class Verbatim(
        override val text: String,
    ) : PkgConfigValuePart
}
