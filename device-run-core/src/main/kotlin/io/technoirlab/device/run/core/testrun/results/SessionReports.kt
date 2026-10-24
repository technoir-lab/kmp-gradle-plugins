package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.Session
import io.technoirlab.device.run.core.storage.GcsUri

/**
 * The result types of sessions and executions, as `resultType` names them in their reports.
 */
internal object ResultType {
    const val PASSED = "PASSED"
    const val FAILED = "FAILED"
    const val ERROR = "ERROR"
    const val TIMED_OUT = "TIMED_OUT"
    const val CANCELLED = "CANCELLED"
    const val UNSPECIFIED = "RESULT_UNSPECIFIED"
}

/**
 * Returns whether the tests of the session ran to completion, as when it passed or failed, so that Device Run writes its job report
 * and outputs of its executions.
 */
internal fun Session.testsRanToCompletion(): Boolean =
    sessionReport?.result?.resultType.let { it == ResultType.PASSED || it == ResultType.FAILED }

/**
 * Returns whether the final report of the session lists the executions of every job.
 */
internal fun Session.reportListsEveryExecution(): Boolean {
    val jobReports = sessionReport?.jobReports.orEmpty()
    return jobReports.isNotEmpty() && jobReports.all { !it.executionReports.isNullOrEmpty() }
}

/**
 * Returns the output files that the reports of the session reference.
 */
internal fun Session.referencedOutputFiles(): List<GcsUri> {
    val jobReports = sessionReport?.jobReports.orEmpty()
    val outputFiles = jobReports.flatMap { job ->
        job.outputFiles.orEmpty() + job.executionReports.orEmpty().flatMap { it.outputFiles.orEmpty() }
    }
    return outputFiles.mapNotNull { file -> file.gcsOutputFile?.path?.let { GcsUri.parseOrNull(it) } }
}
