package io.technoirlab.device.run.core.testrun.results

/**
 * A required test report is missing, unreadable, or malformed.
 */
internal class MalformedReportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
