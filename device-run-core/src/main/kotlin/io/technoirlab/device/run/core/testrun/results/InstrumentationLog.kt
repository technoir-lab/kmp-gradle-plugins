package io.technoirlab.device.run.core.testrun.results

/**
 * The progress and outcome of an instrumentation run, as `am instrument -r` writes them to `instrument.log`.
 *
 * Each entry of the run starts and ends with a status code. An entry is one test, or one event of a whole class, which the runner
 * reports as the test `null`. A class event other than `@Ignore` on the class, such as a failing `@BeforeClass` or an assumption in
 * it, stands for every planned test of the class, while an ignored class counts as one planned test.
 *
 * @property plannedTests the number of tests that the run planned, `numtests`, or null when it started no entry.
 * @property startedEntries the number of entries that started, `INSTRUMENTATION_STATUS_CODE: 1`.
 * @property classEntries the number of started entries of class events that stand for every planned test of their class.
 * @property failedEntries the tests of the entries that failed, `INSTRUMENTATION_STATUS_CODE: -2`, as JUnit describes them, such
 * as `fails(io.example.ExampleTest)`, or `io.example.ExampleTest` for a class event. A test fails once, however many failures it
 * has.
 * @property endedNormally whether the run ended with `INSTRUMENTATION_CODE: -1` and without a `shortMsg`.
 * @property endMessage the `shortMsg` of a run that ended abnormally, such as `Process crashed.`.
 * @property summaryFailureCount the number of failures that the summary at the end of the run lists, as in "There were 2 failures:".
 * @property summaryFailures the failures that the summary lists, in its order. JUnit lists each failure, so a test that also failed
 * in `@After` appears twice.
 */
internal data class InstrumentationLog(
    val plannedTests: Int?,
    val startedEntries: Int,
    val classEntries: Int,
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
