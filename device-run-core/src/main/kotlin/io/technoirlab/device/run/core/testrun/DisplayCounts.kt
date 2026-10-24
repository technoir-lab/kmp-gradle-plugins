package io.technoirlab.device.run.core.testrun

/**
 * Formats [count] with the [noun] for messages, such as `1 test` or `2 tests`.
 */
internal fun displayCount(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
