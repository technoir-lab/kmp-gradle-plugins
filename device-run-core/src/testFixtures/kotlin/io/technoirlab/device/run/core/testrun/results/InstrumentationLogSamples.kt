package io.technoirlab.device.run.core.testrun.results

/**
 * Returns the log of an instrumentation run that started each of its [plannedTests] and ended normally, whose failed entries are
 * [failedTests], as JUnit describes them, and whose summary lists [summaryFailures], by default one failure of each failed test.
 */
internal fun completedRunLog(
    plannedTests: Int,
    failedTests: List<String> = emptyList(),
    summaryFailures: List<InstrumentationLog.SummaryFailure> = failedTests.map { InstrumentationLog.SummaryFailure(it, ASSERTION_ERROR) },
): InstrumentationLog = InstrumentationLog(
    plannedTests = plannedTests,
    startedEntries = plannedTests,
    classEntries = 0,
    failedEntries = failedTests,
    endedNormally = true,
    endMessage = null,
    summaryFailureCount = summaryFailures.size,
    summaryFailures = summaryFailures,
)

/**
 * Returns the log of an instrumentation run that ended abnormally with [endMessage], or without a message, after starting
 * [startedEntries] entries for its [plannedTests], of which [classEntries] stand for the planned tests of a whole class.
 */
internal fun crashedRunLog(
    plannedTests: Int?,
    startedEntries: Int,
    classEntries: Int = 0,
    endMessage: String? = PROCESS_CRASHED,
): InstrumentationLog = InstrumentationLog(
    plannedTests = plannedTests,
    startedEntries = startedEntries,
    classEntries = classEntries,
    failedEntries = emptyList(),
    endedNormally = false,
    endMessage = endMessage,
    summaryFailureCount = 0,
    summaryFailures = emptyList(),
)

private const val ASSERTION_ERROR = "java.lang.AssertionError"
private const val PROCESS_CRASHED = "Process crashed."
