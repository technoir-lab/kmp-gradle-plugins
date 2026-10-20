package io.technoirlab.device.run.core.testrun.job

/**
 * Returns the size of this string encoded as UTF-8, the unit of Device Run length limits.
 */
internal fun String.utf8Size(): Int = encodeToByteArray().size
