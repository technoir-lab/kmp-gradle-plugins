package io.technoirlab.device.run.core.testrun

/**
 * The display name contract of Device Run sessions and jobs.
 */
internal class DisplayNames {
    /**
     * Turns [text] into a display name that Device Run accepts: an ASCII letter or digit followed by letters, digits, `-`, `_`, or
     * spaces, within 63 bytes. Unsupported characters become `-`, leading characters other than letters and digits are dropped, the
     * name is cut to 63 bytes, and trailing spaces, `-`, and `_` are trimmed. A name without letters or digits becomes [fallback].
     */
    fun sanitize(text: String, fallback: String): String = text.replace(UNSUPPORTED_CHARACTERS, "-")
        .dropWhile { !it.isLetterOrDigit() }
        .take(MAX_BYTES)
        .trimEnd(' ', '-', '_')
        .ifEmpty { fallback }

    private companion object {
        private const val MAX_BYTES = 63

        private val UNSUPPORTED_CHARACTERS = Regex("[^A-Za-z0-9_ -]+")
    }
}
