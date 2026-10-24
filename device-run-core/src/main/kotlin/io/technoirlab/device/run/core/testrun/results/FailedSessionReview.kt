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
     * Reviews [session] when Device Run failed it although the [outcomes] of its job report all passed and no [platformFailures]
     * explain the failure, by the [executions] of its job, whose device is [deviceName]. Returns null for any other session.
     */
    fun review(
        session: Session,
        outcomes: List<TestOutcome>?,
        platformFailures: List<PlatformFailure>,
        executions: List<JobExecution>,
        deviceName: String,
    ): Review? {
        val listsOnlyPassingTests = !outcomes.isNullOrEmpty() && outcomes.none { it.status.isFailure }
        if (session.sessionReport?.result?.resultType != ResultType.FAILED || !listsOnlyPassingTests || platformFailures.isNotEmpty()) {
            return null
        }
        val attempts = executions.map(::attempt)
        val problems = listOfNotNull(causeProblem(session)) +
            attempts.filterIsInstance<Attempt.Unconfirmed>().map { it.problem } +
            listOfNotNull("no execution failed".takeIf { attempts.all { it is Attempt.Passed } })
        if (problems.isNotEmpty()) {
            return Review.Unexplained(
                problems.map { problem ->
                    PlatformFailure(
                        RunnerFailure.UNEXPLAINED_SESSION_FAILURE,
                        "Device Run failed the session although its job report lists no failed test: $problem.",
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
     * Returns how [execution] fared according to the session report, or to its own outputs when the report omits it.
     */
    private fun attempt(execution: JobExecution): Attempt {
        val reportEntry = execution.reportEntry ?: return omittedAttempt(execution)
        val result = reportEntry.result
        if (result?.resultType == ResultType.PASSED) {
            return Attempt.Passed(execution)
        }
        val cause = result?.cause?.summary
        return when {
            result?.resultType != ResultType.FAILED ->
                Attempt.Unconfirmed(execution, "Device Run reports ${execution.name} with ${result?.resultType ?: "no result"}")
            cause?.type != CUSTOMER || cause.reason != TEST_CASE_FAILURE -> Attempt.Unconfirmed(
                execution,
                "${execution.name} failed with ${cause?.type ?: "no type"} / ${cause?.reason ?: "no reason"}, not $CUSTOMER / " +
                    TEST_CASE_FAILURE,
            )
            else -> confirmedFailure(execution)
        }
    }

    /**
     * Returns how [execution], which the session report omits, fared according to its instrumentation log.
     */
    private fun omittedAttempt(execution: JobExecution): Attempt {
        val log = execution.instrumentationLog?.takeIf { it.endedNormally }
        if (execution.report == null || log == null) {
            val evidence = if (execution.report == null) "a readable report" else "an instrumentation log that ended normally"
            return Attempt.Unconfirmed(execution, "the session report omits ${execution.name}, whose outputs lack $evidence")
        }
        val failed = log.summaryFailureCount > 0 || log.failedEntries.isNotEmpty()
        return if (failed) confirmedFailure(execution) else Attempt.Passed(execution)
    }

    /**
     * Returns the failed [execution] once its report confirms a failed test, or unconfirmed when the report does not.
     */
    private fun confirmedFailure(execution: JobExecution): Attempt {
        val report = execution.report
            ?: return Attempt.Unconfirmed(execution, "${execution.name} failed, but its report is missing or malformed")
        return if (report.testCases.any { it.failures.isNotEmpty() || it.error != null }) {
            Attempt.FailedTests(execution)
        } else {
            Attempt.Unconfirmed(execution, "${execution.name} failed, but its report lists no failed test")
        }
    }

    private fun passingWarning(session: Session, attempts: List<Attempt>, deviceName: String): ReportWarning {
        // The cause of the job names the failed tests, while the cause of the session names the failed job.
        val jobCause = session.sessionReport?.jobReports.orEmpty().firstNotNullOfOrNull { it.result?.cause?.summary?.reason }
        val reason = jobCause ?: session.sessionReport?.result?.cause?.summary?.reason ?: ResultType.FAILED
        val failedShards = attempts.groupBy { it.execution.shard }
            .filterValues { shardAttempts -> shardAttempts.all { it is Attempt.FailedTests } }
            .keys
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
     * An attempt of a shard, as the review sees it.
     */
    private sealed interface Attempt {
        val execution: JobExecution

        /**
         * The attempt passed every test.
         */
        data class Passed(
            override val execution: JobExecution,
        ) : Attempt

        /**
         * The attempt failed, and its report lists a failed test.
         */
        data class FailedTests(
            override val execution: JobExecution,
        ) : Attempt

        /**
         * The attempt cannot confirm the review, which [problem] explains.
         */
        data class Unconfirmed(
            override val execution: JobExecution,
            val problem: String,
        ) : Attempt
    }

    private companion object {
        private const val CUSTOMER = "CUSTOMER"
        private const val TEST_CASE_FAILURE = "TEST_CASE_FAILURE"
        private const val SESSION_HAS_FAILED_JOBS = "SESSION_HAS_FAILED_JOBS"
    }
}
