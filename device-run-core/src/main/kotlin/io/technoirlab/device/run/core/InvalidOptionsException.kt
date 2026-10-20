package io.technoirlab.device.run.core

/**
 * The options of a [DeviceRunner] operation are invalid.
 *
 * The message lists the errors after the summary.
 *
 * @property summary the heading of the errors, such as `Invalid Device Run configuration`.
 * @property errors the errors of the options, each naming the option that causes it.
 */
class InvalidOptionsException internal constructor(
    val summary: String,
    val errors: List<String>,
) : DeviceRunException(errors.joinToString("\n", prefix = "$summary:\n") { "- $it" })
