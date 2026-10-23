package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.Session

/**
 * Reviews a session that Device Run failed although its job report lists tests that all passed, and no platform failure occurred.
 *
 * Device Run fails a shard when none of its attempts passes every test, while its job report counts each test that passed in some
 * attempt as passed. The tests of such a session pass when Device Run blames failed tests and every attempt confirms it: the
 * session failed with `SESSION_HAS_FAILED_JOBS`; every execution that the session report lists passed, or failed with the cause
 * `CUSTOMER` / `TEST_CASE_FAILURE`; and at least one attempt failed, and the own report of each failed attempt lists a failed test.
 *
 * The final session report can omit executions, so an attempt with outputs that the report omits counts when it has a readable
 * report and an instrumentation log that ended normally, which tells whether the attempt failed.
 */
internal class FailedSessionReview {
    /**
     * Reviews [session] by the [executions] of its job, whose device is [deviceName].
     */
    fun review(session: Session, executions: List<JobExecution>, deviceName: String): Review {
        val attempts = executions.sortedBy { it.name }.map(::attempt)
        val problems = listOfNotNull(causeProblem(session)) +
            attempts.mapNotNull { it.problem } +
            listOfNotNull("no execution failed".takeIf { attempts.none { it.failed } })
        if (problems.isNotEmpty()) {
            return Review.Unexplained(
                problems.map { problem ->
                    PlatformFailure(
                        reason = UNEXPLAINED_FAILURE,
                        classification = REPORTING,
                        message = "Device Run failed the session although its job report lists no failed test: $problem.",
                    )
                },
            )
        }
        return Review.TestsPass(passingWarning(session, attempts, deviceName))
    }

    /**
     * Returns why the cause of the failure of [session] does not blame failed tests, or null when it does.
     */
    private fun causeProblem(session: Session): String? {
        val cause = session.sessionReport?.result?.cause?.summary
        return when (cause?.reason) {
            SESSION_HAS_FAILED_JOBS -> null
            null -> "Device Run gave no cause"
            else -> "the cause is ${cause.reason}, not $SESSION_HAS_FAILED_JOBS"
        }
    }

    /**
     * Returns whether [execution] failed, and why it cannot confirm the review.
     */
    private fun attempt(execution: JobExecution): Attempt {
        val reportEntry = execution.reportEntry ?: return omittedAttempt(execution)
        val result = reportEntry.result
        if (result?.resultType == PASSED) {
            return Attempt(execution, failed = false, problem = null)
        }
        val cause = result?.cause?.summary
        val problem = when {
            result?.resultType != FAILED -> "Device Run reports ${execution.name} with ${result?.resultType ?: "no result"}"
            cause?.type != CUSTOMER || cause.reason != TEST_CASE_FAILURE ->
                "${execution.name} failed with ${cause?.type ?: "no type"} / ${cause?.reason ?: "no reason"}, not $CUSTOMER / " +
                    TEST_CASE_FAILURE
            else -> reportProblem(execution)
        }
        return Attempt(execution, failed = true, problem = problem)
    }

    /**
     * Returns whether [execution], which the session report omits, failed according to its instrumentation log, and why it
     * cannot confirm the review.
     */
    private fun omittedAttempt(execution: JobExecution): Attempt {
        val log = execution.instrumentationLog?.takeIf { it.endedNormally }
        if (execution.report == null || log == null) {
            val evidence = if (execution.report == null) "a readable report" else "an instrumentation log that ended normally"
            return Attempt(execution, failed = false, problem = "the session report omits ${execution.name}, whose outputs lack $evidence")
        }
        val failed = log.summaryFailureCount > 0 || log.failedEntries.isNotEmpty()
        return Attempt(execution, failed, problem = if (failed) reportProblem(execution) else null)
    }

    /**
     * Returns why the report of [execution], which failed, does not confirm a failed test, or null when it does.
     */
    private fun reportProblem(execution: JobExecution): String? {
        val report = execution.report ?: return "${execution.name} failed, but its report is missing or malformed"
        val listsFailedTest = report.testCases.any { it.failures.isNotEmpty() || it.error != null }
        return if (listsFailedTest) null else "${execution.name} failed, but its report lists no failed test"
    }

    private fun passingWarning(session: Session, attempts: List<Attempt>, deviceName: String): ReportWarning {
        // The cause of the job names the failed tests, while the cause of the session names the failed job.
        val jobCause = session.sessionReport?.jobReports.orEmpty().firstNotNullOfOrNull { it.result?.cause?.summary?.reason }
        val reason = jobCause ?: session.sessionReport?.result?.cause?.summary?.reason ?: FAILED
        val failedShards = attempts.groupBy { it.execution.shard }.filterValues { shardAttempts -> shardAttempts.all { it.failed } }.keys
        val sessionId = session.name?.substringAfterLast('/')
        val failure = if (failedShards.isEmpty()) {
            "Device Run failed session $sessionId ($reason)."
        } else {
            "Device Run failed session $sessionId ($reason) because no attempt of ${failedShards.sorted().joinToString(" or ")} passed " +
                "every test."
        }
        return ReportWarning(reason, "$failure Every test passed in some attempt, so the tests of $deviceName pass.")
    }

    /**
     * The result of reviewing a failed session.
     */
    sealed interface Review {
        /**
         * Device Run failed the session only because no attempt of a shard passed every test, so its tests pass, with a [warning].
         */
        data class TestsPass(
            val warning: ReportWarning,
        ) : Review

        /**
         * The failure of the session is not confirmed to come only from attempts that failed some test, which the [failures]
         * explain.
         */
        data class Unexplained(
            val failures: List<PlatformFailure>,
        ) : Review
    }

    /**
     * An attempt of a shard, whether it [failed], and why it cannot confirm the review, if it cannot.
     */
    private data class Attempt(
        val execution: JobExecution,
        val failed: Boolean,
        val problem: String?,
    )

    private companion object {
        private const val PASSED = "PASSED"
        private const val FAILED = "FAILED"
        private const val CUSTOMER = "CUSTOMER"
        private const val TEST_CASE_FAILURE = "TEST_CASE_FAILURE"
        private const val SESSION_HAS_FAILED_JOBS = "SESSION_HAS_FAILED_JOBS"
        private const val REPORTING = "REPORTING"
        private const val UNEXPLAINED_FAILURE = "UNEXPLAINED_SESSION_FAILURE"
    }
}
