package io.technoirlab.device.run.core.testrun.results

/**
 * The progress and outcome of an instrumentation run, as `am instrument -r` writes them to `instrument.log`.
 *
 * Each entry of the run starts and ends with a status code. An entry is one test, or one class-level event, such as a failing
 * `@BeforeClass`, an assumption in it, or `@Ignore` on a class, that stands for several tests.
 *
 * @property plannedTests the number of tests that the run planned, `numtests`, or null when it started no entry.
 * @property startedEntries the number of entries that started, `INSTRUMENTATION_STATUS_CODE: 1`.
 * @property failedEntries the tests of the entries that failed, `INSTRUMENTATION_STATUS_CODE: -2`, as JUnit describes them, such
 * as `fails(io.example.ExampleTest)`, or `io.example.ExampleTest` for a class-level event.
 * @property endedNormally whether the run ended with `INSTRUMENTATION_CODE: -1` and without a `shortMsg`.
 * @property endMessage the `shortMsg` of a run that ended abnormally, such as `Process crashed.`.
 * @property summaryFailureCount the number of failures that the summary at the end of the run lists, as in "There were 2 failures:".
 * @property summaryFailures the failures that the summary lists, in its order.
 */
internal data class InstrumentationLog(
    val plannedTests: Int?,
    val startedEntries: Int,
    val failedEntries: List<String>,
    val endedNormally: Boolean,
    val endMessage: String?,
    val summaryFailureCount: Int,
    val summaryFailures: List<SummaryFailure>,
) {
    /**
     * A failure that the summary of an instrumentation run lists.
     *
     * @property test the test as JUnit describes it, such as `fails(io.example.ExampleTest)`, `io.example.ExampleTest` for a class,
     * or `(io.example.ExampleTest)` for a class that Android Test Orchestrator ran.
     * @property firstLine the first line of the failure, such as `java.lang.IllegalStateException: Fails in @BeforeClass`.
     */
    data class SummaryFailure(
        val test: String,
        val firstLine: String,
    )
}
