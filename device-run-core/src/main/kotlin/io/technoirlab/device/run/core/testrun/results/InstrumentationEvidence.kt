package io.technoirlab.device.run.core.testrun.results

/**
 * Finds what the instrumentation logs of the executions of a job show, but the reports of Device Run omit: tests that never ran
 * because an execution ended early, and failures that only the summary of an instrumentation run lists.
 *
 * Both are platform failures, as AGP's instrumentation runner fails a run that does not complete. When class events leave it
 * uncertain whether tests never ran, a warning says so instead. A missing or unreadable log shows nothing.
 */
internal class InstrumentationEvidence {
    /**
     * Returns what the instrumentation logs of [executions] show.
     *
     * Tests were lost when an execution ended abnormally, such as with `Process crashed.`, after starting fewer entries than it
     * planned tests, and no attempt of its shard ended normally with at least as many planned tests. A class event, such as an
     * assumption in `@BeforeClass`, is one entry for several planned tests, so a run with class events may have started every test;
     * it gets a warning instead.
     *
     * Failures were omitted when the summary of an execution lists failures of tests that no failed entry reports, as for a failing
     * `@BeforeClass` under Android Test Orchestrator. JUnit lists each failure of a test, such as a failure in `@After` besides the
     * failure of the test, while the runner reports the test once.
     */
    fun examine(executions: List<JobExecution>): Findings {
        val sortedExecutions = executions.sortedBy { it.name }
        val earlyEnds = sortedExecutions.mapNotNull { earlyEnd(it, executions) }
        return Findings(
            failures = earlyEnds.filter { it.log.classEntries == 0 }.map(::lostTests) +
                sortedExecutions.mapNotNull(::unreportedFailures),
            warnings = earlyEnds.filter { it.log.classEntries > 0 }.map(::possiblyLostTests),
        )
    }

    /**
     * Returns how [execution] ended early: abnormally, after starting fewer entries than it planned tests, without an attempt of
     * its shard that ended normally with at least as many planned tests. Returns null for any other execution.
     */
    private fun earlyEnd(execution: JobExecution, executions: List<JobExecution>): EarlyEnd? {
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
        return if (completedByRetry) null else EarlyEnd(execution.name, log, plannedTests)
    }

    private fun lostTests(earlyEnd: EarlyEnd): PlatformFailure = PlatformFailure(
        reason = INCOMPLETE_TEST_RUN,
        classification = INSTRUMENTATION,
        message = "The instrumentation of ${earlyEnd.execution} ended ${earlyEnd.description} after starting " +
            "${earlyEnd.log.startedEntries} of its ${earlyEnd.plannedTests} tests, and no retry ran the rest, so " +
            "${tests(earlyEnd.unstartedTests)} did not run.",
    )

    private fun possiblyLostTests(earlyEnd: EarlyEnd): ReportWarning = ReportWarning(
        reason = POSSIBLY_INCOMPLETE_TEST_RUN,
        message = "The instrumentation of ${earlyEnd.execution} ended ${earlyEnd.description} after starting " +
            "${earlyEnd.log.startedEntries} entries for its ${earlyEnd.plannedTests} tests, and no retry ran the rest. Entries of " +
            "class events, such as an assumption in @BeforeClass, stand for several tests, so up to ${tests(earlyEnd.unstartedTests)} " +
            "may not have run.",
    )

    private fun unreportedFailures(execution: JobExecution): PlatformFailure? {
        val log = execution.instrumentationLog ?: return null
        val reportedTests = log.failedEntries.toSet()
        val unreported = log.summaryFailures.filter { it.test !in reportedTests }
        if (unreported.isEmpty()) {
            return null
        }
        val quotes = unreported.map { "${it.test} ${it.firstLine}".trim() }.distinct()
        val failures = if (unreported.size == 1) "1 failure" else "${unreported.size} failures"
        return PlatformFailure(
            reason = UNREPORTED_TEST_FAILURES,
            classification = INSTRUMENTATION,
            message = "The instrumentation summary of ${execution.name} lists $failures that the test results omit: " +
                quotes.joinToString("; "),
        )
    }

    private fun tests(count: Int): String = if (count == 1) "1 test" else "$count tests"

    /**
     * What the instrumentation logs of the executions of a job show.
     *
     * @property failures the platform failures: tests that never ran, and failures that the reports omit.
     * @property warnings the runs that ended early with class events, which may have left tests unrun.
     */
    data class Findings(
        val failures: List<PlatformFailure>,
        val warnings: List<ReportWarning>,
    )

    /**
     * An instrumentation run of [execution] that ended early, after starting fewer entries than its [plannedTests].
     */
    private data class EarlyEnd(
        val execution: String,
        val log: InstrumentationLog,
        val plannedTests: Int,
    ) {
        val description: String
            get() = log.endMessage?.let { "with \"$it\"" } ?: "abnormally"

        val unstartedTests: Int
            get() = plannedTests - log.startedEntries
    }

    private companion object {
        private const val INSTRUMENTATION = "INSTRUMENTATION"
        private const val INCOMPLETE_TEST_RUN = "INCOMPLETE_TEST_RUN"
        private const val POSSIBLY_INCOMPLETE_TEST_RUN = "POSSIBLY_INCOMPLETE_TEST_RUN"
        private const val UNREPORTED_TEST_FAILURES = "UNREPORTED_TEST_FAILURES"
    }
}
