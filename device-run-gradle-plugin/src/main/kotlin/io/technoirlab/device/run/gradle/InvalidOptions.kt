package io.technoirlab.device.run.gradle

import io.technoirlab.device.run.core.InvalidOptionsException
import org.gradle.api.InvalidUserDataException
import org.gradle.api.problems.ProblemReporter

/**
 * Runs [operation] and fails with a problem for each invalid option when the runner rejects the options.
 *
 * The failure keeps the summary of the [InvalidOptionsException], which remains its cause.
 */
@Suppress("UnstableApiUsage")
internal fun <T> ProblemReporter.reportingInvalidOptions(operation: () -> T): T = try {
    operation()
} catch (e: InvalidOptionsException) {
    val problems = e.errors.map { error -> create(DeviceRunProblems.INVALID_OPTION) { contextualLabel(error) } }
    throw throwing(InvalidUserDataException(e.summary, e), problems)
}
