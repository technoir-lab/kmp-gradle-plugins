package io.technoirlab.device.run.core.testrun.results

/**
 * Finds what the instrumentation logs of the executions of a job show, but the reports of Device Run omit: tests that never ran
 * because an execution ended early, and failures that only the summary of an instrumentation run lists.
 *
 * Both are platform failures, as AGP's instrumentation runner fails a run that does not complete. A missing or unreadable log
 * shows nothing.
 */
internal class InstrumentationEvidence {
    /**
     * Returns the platform failures that the instrumentation logs of [executions] show.
     *
     * Tests were lost when an execution ended abnormally, such as with `Process crashed.`, after starting fewer entries than it
     * planned, and no attempt of its shard ended normally with at least as many planned tests. A failing class-level event is one
     * entry for several tests, so the number of lost tests is an upper bound.
     *
     * Failures were omitted when the summary of an execution lists more failures than its failed entries, as for a failing
     * `@BeforeClass` under Android Test Orchestrator.
     */
    fun failures(executions: List<JobExecution>): List<PlatformFailure> {
        val sortedExecutions = executions.sortedBy { it.name }
        return sortedExecutions.mapNotNull { incompleteRun(it, executions) } + sortedExecutions.mapNotNull(::unreportedFailures)
    }

    private fun incompleteRun(execution: JobExecution, executions: List<JobExecution>): PlatformFailure? {
        val log = execution.instrumentationLog ?: return null
        val plannedTests = log.plannedTests ?: return null
        if (log.endedNormally || log.startedEntries >= plannedTests) {
            return null
        }
        val completedByRetry = executions.any { attempt ->
            val attemptLog = attempt.instrumentationLog
            attempt.shard == execution.shard && attemptLog != null && attemptLog.endedNormally &&
                (attemptLog.plannedTests ?: 0) >= plannedTests
        }
        if (completedByRetry) {
            return null
        }
        val end = log.endMessage?.let { "with \"$it\"" } ?: "abnormally"
        val lostTests = plannedTests - log.startedEntries
        return PlatformFailure(
            reason = INCOMPLETE_TEST_RUN,
            classification = INSTRUMENTATION,
            message = "The instrumentation of ${execution.name} ended $end after starting ${log.startedEntries} of its $plannedTests " +
                "tests, and no retry ran the rest, so up to ${tests(lostTests)} did not run.",
        )
    }

    private fun unreportedFailures(execution: JobExecution): PlatformFailure? {
        val log = execution.instrumentationLog ?: return null
        val unreported = log.summaryFailureCount - log.failedEntries.size
        if (unreported <= 0) {
            return null
        }
        val quotes = unmatched(log.summaryFailures, log.failedEntries).map { "${it.test} ${it.firstLine}".trim() }.distinct()
        val failures = if (unreported == 1) "1 failure" else "$unreported failures"
        return PlatformFailure(
            reason = UNREPORTED_TEST_FAILURES,
            classification = INSTRUMENTATION,
            message = "The instrumentation summary of ${execution.name} lists $failures that the test results omit: " +
                quotes.joinToString("; "),
        )
    }

    /**
     * Returns the [summaryFailures] that no entry of [failedEntries] reports, each entry accounting for one summary failure.
     */
    private fun unmatched(
        summaryFailures: List<InstrumentationLog.SummaryFailure>,
        failedEntries: List<String>,
    ): List<InstrumentationLog.SummaryFailure> {
        val unmatchedEntries = failedEntries.toMutableList()
        return summaryFailures.filterNot { unmatchedEntries.remove(it.test) }
    }

    private fun tests(count: Int): String = if (count == 1) "1 test" else "$count tests"

    private companion object {
        private const val INSTRUMENTATION = "INSTRUMENTATION"
        private const val INCOMPLETE_TEST_RUN = "INCOMPLETE_TEST_RUN"
        private const val UNREPORTED_TEST_FAILURES = "UNREPORTED_TEST_FAILURES"
    }
}
