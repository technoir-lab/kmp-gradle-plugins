package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.ExecutionReport
import com.google.api.services.devicerun.v1alpha.model.GcsPath
import com.google.api.services.devicerun.v1alpha.model.IssueSummary
import com.google.api.services.devicerun.v1alpha.model.JobReport
import com.google.api.services.devicerun.v1alpha.model.OutputFile
import com.google.api.services.devicerun.v1alpha.model.Result
import com.google.api.services.devicerun.v1alpha.model.ResultCause
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionReport
import com.google.api.services.devicerun.v1alpha.model.Status
import com.google.api.services.devicerun.v1alpha.model.Warning
import java.time.Instant

/**
 * Builds the report of a Device Run session as the client models hold it: the result and status of the session, when it started
 * and ended, and the reports of its jobs and of their executions.
 *
 * What the builder does not set stays absent, as in the final reports of Device Run, which leave out empty collections. The models
 * hold mutable lists, as the models that the client parses do.
 */
internal class SessionReportBuilder {
    private val jobReports = mutableListOf<JobReport>()
    private var result: Result? = null
    private var status: Status? = null
    private var startTime: Instant? = null

    /**
     * Sets the result of the session to [resultType], such as `FAILED`, with a cause of [type], [reason], and [message] when any of
     * them is given.
     */
    fun result(resultType: String, type: String? = null, reason: String? = null, message: String? = null) {
        result = reportResult(resultType, type, reason, message)
    }

    /**
     * Sets the status of the session to [statusType], such as `RUNNING`, with its [progressMessages].
     */
    fun status(statusType: String, vararg progressMessages: String) {
        status = Status().setStatusType(statusType).setProgressMessages(progressMessages.toMutableList().ifEmpty { null })
    }

    /**
     * Sets when the session started.
     */
    fun startTime(time: Instant) {
        startTime = time
    }

    /**
     * Adds the report of the job [displayName], which names its directory, as [configure] builds it.
     */
    fun job(displayName: String, configure: JobReportBuilder.() -> Unit = {}) {
        jobReports += JobReportBuilder(displayName).apply(configure).build()
    }

    /**
     * Returns the report of the session.
     */
    fun build(): SessionReport = SessionReport()
        .setResult(result)
        .setStatus(status)
        .setStartTime(startTime?.toString())
        .setJobReports(jobReports.toMutableList().ifEmpty { null })

    /**
     * Builds the report of the job [displayName]: its result, the output files and warnings of the job, and the reports of its
     * executions.
     */
    class JobReportBuilder(
        private val displayName: String,
    ) {
        private val outputFiles = mutableListOf<OutputFile>()
        private val warnings = mutableListOf<Warning>()
        private val executionReports = mutableListOf<ExecutionReport>()
        private var result: Result? = null

        /**
         * Sets the result of the job to [resultType], with a cause of [type], [reason], and [message] when any of them is given.
         */
        fun result(resultType: String, type: String? = null, reason: String? = null, message: String? = null) {
            result = reportResult(resultType, type, reason, message)
        }

        /**
         * Adds the output file at [path], a `gs://` location that need not name an object.
         */
        fun outputFile(path: String) {
            outputFiles += outputFileAt(path)
        }

        /**
         * Adds a warning about the job with [reason] and [message].
         */
        fun warning(reason: String, message: String) {
            warnings += reportWarning(reason, message)
        }

        /**
         * Adds the report of the execution [displayName], such as `execution-000-rerun-000`, as [configure] builds it.
         */
        fun execution(displayName: String, configure: ExecutionReportBuilder.() -> Unit = {}) {
            executionReports += ExecutionReportBuilder(displayName).apply(configure).build()
        }

        /**
         * Returns the report of the job.
         */
        fun build(): JobReport = JobReport()
            .setDisplayName(displayName)
            .setResult(result)
            .setOutputFiles(outputFiles.toMutableList().ifEmpty { null })
            .setWarnings(warnings.toMutableList().ifEmpty { null })
            .setExecutionReports(executionReports.toMutableList().ifEmpty { null })
    }

    /**
     * Builds the report of the execution [displayName]: its result, when it started, and its output files.
     */
    class ExecutionReportBuilder(
        private val displayName: String,
    ) {
        private val outputFiles = mutableListOf<OutputFile>()
        private var result: Result? = null
        private var startTime: String? = null

        /**
         * Sets the result of the execution to [resultType], with a cause of [type], [reason], and [message] when any of them is
         * given.
         */
        fun result(resultType: String, type: String? = null, reason: String? = null, message: String? = null) {
            result = reportResult(resultType, type, reason, message)
        }

        /**
         * Sets when the execution started.
         */
        fun startTime(time: Instant) {
            startTime = time.toString()
        }

        /**
         * Sets when the execution started to [timestamp] as Device Run writes it, which need not be a valid RFC 3339 timestamp.
         */
        fun startTime(timestamp: String) {
            startTime = timestamp
        }

        /**
         * Adds the output file at [path], a `gs://` location that need not name an object.
         */
        fun outputFile(path: String) {
            outputFiles += outputFileAt(path)
        }

        /**
         * Returns the report of the execution.
         */
        fun build(): ExecutionReport = ExecutionReport()
            .setDisplayName(displayName)
            .setResult(result)
            .setStartTime(startTime)
            .setOutputFiles(outputFiles.toMutableList().ifEmpty { null })
    }
}

/**
 * Returns the session report that [configure] builds.
 */
internal fun buildSessionReport(configure: SessionReportBuilder.() -> Unit): SessionReport = SessionReportBuilder().apply(configure).build()

/**
 * Returns a session named [name], such as `projects/example-project/locations/global/sessions/session-1`, or without a name, whose
 * report [configure] builds.
 */
internal fun buildSession(name: String? = null, configure: SessionReportBuilder.() -> Unit): Session =
    Session().setName(name).setSessionReport(buildSessionReport(configure))

/**
 * Returns a result of [resultType], such as `FAILED`, with a cause of [type], [reason], and [message] when any of them is given.
 */
internal fun reportResult(resultType: String, type: String? = null, reason: String? = null, message: String? = null): Result {
    val cause = if (reason != null || type != null || message != null) {
        ResultCause().setSummary(IssueSummary().setReason(reason).setType(type).setMessage(message))
    } else {
        null
    }
    return Result().setResultType(resultType).setCause(cause)
}

/**
 * Returns a warning of a job or an execution with [reason] and [message].
 */
internal fun reportWarning(reason: String, message: String): Warning =
    Warning().setSummary(IssueSummary().setReason(reason).setMessage(message))

private fun outputFileAt(path: String): OutputFile = OutputFile().setGcsOutputFile(GcsPath().setPath(path))
