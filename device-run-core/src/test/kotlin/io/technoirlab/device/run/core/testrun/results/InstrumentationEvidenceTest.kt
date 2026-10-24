package io.technoirlab.device.run.core.testrun.results

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class InstrumentationEvidenceTest {
    private val evidence = InstrumentationEvidence()

    @ParameterizedTest
    @ValueSource(ints = [4, 5])
    fun `finds no lost tests of a crashed shard whose retry ended normally with at least as many planned tests`(retryPlannedTests: Int) {
        val executions = listOf(
            attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 2)),
            attempt("execution-000-rerun-000", completedRunLog(plannedTests = retryPlannedTests)),
        )

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(NO_FINDINGS)
    }

    @Test
    fun `gives no warning about a crashed shard with class events whose retry ended normally with as many planned tests`() {
        val executions = listOf(
            attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 3, classEntries = 1)),
            attempt("execution-000-rerun-000", completedRunLog(plannedTests = 4)),
        )

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(NO_FINDINGS)
    }

    @Test
    fun `keeps the lost tests of a crashed shard whose retry planned fewer tests`() {
        // A retry reduced to failed tests plans only the tests that failed, such as the one that crashed.
        val executions = listOf(
            attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 2)),
            attempt("execution-000-rerun-000", completedRunLog(plannedTests = 1)),
        )

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(InstrumentationEvidence.Findings(failures = listOf(LOST_TESTS), warnings = emptyList()))
    }

    @Test
    fun `keeps the lost tests of a crashed shard when only an attempt of another shard ended normally`() {
        val executions = listOf(
            attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 2)),
            attempt("execution-001", completedRunLog(plannedTests = 4)),
        )

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(InstrumentationEvidence.Findings(failures = listOf(LOST_TESTS), warnings = emptyList()))
    }

    @Test
    fun `keeps the lost tests of a crashed shard whose retry ended abnormally too`() {
        // The retry started every planned test, so it lost none itself.
        val executions = listOf(
            attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 2)),
            attempt("execution-000-rerun-000", crashedRunLog(plannedTests = 4, startedEntries = 4)),
        )

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(InstrumentationEvidence.Findings(failures = listOf(LOST_TESTS), warnings = emptyList()))
    }

    @Test
    fun `warns instead of failing when class events leave it uncertain whether a crashed shard lost tests`() {
        val executions = listOf(attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 3, classEntries = 1)))

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(
            InstrumentationEvidence.Findings(
                failures = emptyList(),
                warnings = listOf(
                    ReportWarning(
                        reason = "POSSIBLY_INCOMPLETE_TEST_RUN",
                        message = "The instrumentation of execution-000 ended with \"Process crashed.\" after starting 3 entries for its " +
                            "4 tests, and no retry ran the rest. Entries of class events, such as an assumption in @BeforeClass, stand " +
                            "for several tests, so up to 1 test may not have run.",
                    ),
                ),
            ),
        )
    }

    @Test
    fun `finds no lost tests of a run that ended abnormally after starting every planned test`() {
        val executions = listOf(attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 4)))

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(NO_FINDINGS)
    }

    @Test
    fun `describes a run that ended abnormally without a message`() {
        val executions = listOf(attempt("execution-000", crashedRunLog(plannedTests = 4, startedEntries = 3, endMessage = null)))

        val findings = evidence.examine(executions)

        assertThat(findings.failures).containsExactly(
            PlatformFailure(
                RunnerFailure.INCOMPLETE_TEST_RUN,
                "The instrumentation of execution-000 ended abnormally after starting 3 of its 4 tests, and no retry ran the rest, so 1 " +
                    "test did not run.",
            ),
        )
    }

    @Test
    fun `finds nothing in executions without a readable instrumentation log or planned tests`() {
        val executions = listOf(
            attempt("execution-000", log = null),
            attempt("execution-001", crashedRunLog(plannedTests = null, startedEntries = 0)),
        )

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(NO_FINDINGS)
    }

    @Test
    fun `reports the failures that only the summary of a run lists`() {
        // Android Test Orchestrator lists a failure in @BeforeClass once for each test of the class, without a failed entry.
        val classFailure = InstrumentationLog.SummaryFailure(
            test = "io.example.SetupTest",
            firstLine = "java.lang.IllegalStateException: Fails in @BeforeClass",
        )
        val log = completedRunLog(plannedTests = 2, summaryFailures = listOf(classFailure, classFailure))
        val executions = listOf(attempt("execution-000", log))

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(
            InstrumentationEvidence.Findings(
                failures = listOf(
                    PlatformFailure(
                        RunnerFailure.UNREPORTED_TEST_FAILURES,
                        "The instrumentation summary of execution-000 lists 2 failures that the test results omit: " +
                            "io.example.SetupTest java.lang.IllegalStateException: Fails in @BeforeClass",
                    ),
                ),
                warnings = emptyList(),
            ),
        )
    }

    @Test
    fun `finds no unreported failures when each failure that the summary lists belongs to a failed entry`() {
        // JUnit lists each failure of a test, such as a failure in @After besides the failure of the test.
        val summaryFailures = listOf(
            InstrumentationLog.SummaryFailure(FAILED_TEST, "java.lang.AssertionError: Fails in the test"),
            InstrumentationLog.SummaryFailure(FAILED_TEST, "java.lang.AssertionError: Fails in @After"),
        )
        val log = completedRunLog(plannedTests = 2, failedTests = listOf(FAILED_TEST), summaryFailures = summaryFailures)
        val executions = listOf(attempt("execution-000", log))

        val findings = evidence.examine(executions)

        assertThat(findings).isEqualTo(NO_FINDINGS)
    }

    private fun attempt(name: String, log: InstrumentationLog?): JobExecution =
        JobExecution(name = name, reportEntry = null, report = null, instrumentationLog = lazyOf(log))

    private companion object {
        private const val FAILED_TEST = "fails(io.example.ExampleTest)"
        private val NO_FINDINGS = InstrumentationEvidence.Findings(failures = emptyList(), warnings = emptyList())
        private val LOST_TESTS = PlatformFailure(
            RunnerFailure.INCOMPLETE_TEST_RUN,
            "The instrumentation of execution-000 ended with \"Process crashed.\" after starting 2 of its 4 tests, and no retry ran the " +
                "rest, so 2 tests did not run.",
        )
    }
}
