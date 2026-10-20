package io.technoirlab.device.run.core.testrun

import java.time.Duration

/**
 * Formats this duration as a protobuf `Duration` JSON string, such as `300s` or `1.500s`, without losing precision.
 *
 * The fraction has 3, 6, or 9 digits, as the protobuf JSON mapping prescribes.
 */
internal fun Duration.toProtoDuration(): String {
    val sign = if (isNegative) "-" else ""
    val magnitude = abs()
    val nanos = magnitude.nano
    val fraction = when {
        nanos == 0 -> ""
        nanos % NANOS_PER_MILLI == 0 -> "." + (nanos / NANOS_PER_MILLI).toString().padStart(3, '0')
        nanos % NANOS_PER_MICRO == 0 -> "." + (nanos / NANOS_PER_MICRO).toString().padStart(6, '0')
        else -> "." + nanos.toString().padStart(9, '0')
    }
    return "$sign${magnitude.seconds}${fraction}s"
}

private const val NANOS_PER_MICRO = 1_000
private const val NANOS_PER_MILLI = 1_000_000
