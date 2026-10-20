package io.technoirlab.device.run.core.testrun

import java.time.Duration
import kotlin.time.toKotlinDuration

/**
 * Formats this duration for messages, such as `5m`, `1h 30m`, or `3h 0m 0.000000001s`.
 */
internal fun Duration.toDisplayString(): String = toKotlinDuration().toString()
