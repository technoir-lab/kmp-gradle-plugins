package io.technoirlab.device.run.core

/**
 * Errors found while validating the options of one operation, which are reported together under [summary].
 *
 * Each error names the option that causes it.
 */
internal class ValidationErrors(
    private val summary: String = "Invalid Device Run configuration",
) {
    private val messages = mutableListOf<String>()

    /**
     * Adds an [error] of the DSL [property].
     */
    fun add(property: String, error: String) {
        add("$property $error.")
    }

    /**
     * Adds an error [message] that names its option.
     */
    fun add(message: String) {
        messages += message
    }

    /**
     * @throws InvalidOptionsException listing every added error, when there is one.
     */
    fun throwIfAny() {
        if (messages.isNotEmpty()) {
            throw InvalidOptionsException(summary, messages.toList())
        }
    }
}
