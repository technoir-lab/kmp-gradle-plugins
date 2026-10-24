package io.technoirlab.device.run.core.testrun.results

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TestOutcomeMapperTest {
    private val mapper = TestOutcomeMapper()

    @Test
    fun `passes a flaky test and keeps the failure of each failed attempt without the repetition of the job report`() {
        val testCase = testCase(flaky = true, failures = listOf(jobReportFailure(FIRST_TRACE), jobReportFailure(SECOND_TRACE)))

        val outcome = mapper.map(testCase)

        assertThat(outcome.status).isEqualTo(TestOutcome.Status.PASSED)
        assertThat(outcome.flaky).isTrue()
        assertThat(outcome.detail).isNull()
        assertThat(outcome.failedAttempts).containsExactly(
            TestOutcome.Detail("java.lang.AssertionError", "Fails on the first attempt", FIRST_TRACE),
            TestOutcome.Detail("java.lang.AssertionError", "Fails on the second attempt", SECOND_TRACE),
        )
    }

    @Test
    fun `fails a test that failed every attempt with the failure of its first attempt`() {
        val testCase = testCase(flaky = false, failures = listOf(jobReportFailure(FIRST_TRACE), jobReportFailure(SECOND_TRACE)))

        val outcome = mapper.map(testCase)

        assertThat(outcome.status).isEqualTo(TestOutcome.Status.FAILED)
        assertThat(outcome.flaky).isFalse()
        assertThat(outcome.detail).isEqualTo(outcome.failedAttempts.first())
        assertThat(outcome.failedAttempts).hasSize(2)
    }

    @Test
    fun `fails a test with an error that Device Run marks flaky, a combination it was never observed to report`() {
        val testCase = testCase(flaky = true, failures = emptyList(), error = JUnitOutcomeElement(null, null, FIRST_TRACE))

        val outcome = mapper.map(testCase)

        assertThat(outcome.status).isEqualTo(TestOutcome.Status.ERROR)
        assertThat(outcome.flaky).isFalse()
        assertThat(outcome.detail?.trace).isEqualTo(FIRST_TRACE)
        assertThat(outcome.failedAttempts).isEmpty()
    }

    @Test
    fun `keeps a failed attempt without a trace`() {
        val testCase = testCase(flaky = true, failures = listOf(JUnitOutcomeElement(null, null, "")))

        val outcome = mapper.map(testCase)

        assertThat(outcome.failedAttempts).containsExactly(TestOutcome.Detail(type = null, message = "", trace = ""))
    }

    private fun testCase(flaky: Boolean, failures: List<JUnitOutcomeElement>, error: JUnitOutcomeElement? = null): JUnitTestCase =
        JUnitTestCase(
            className = "a.FlakyTest",
            name = "flakes",
            time = "0.012",
            flaky = flaky,
            failures = failures,
            error = error,
            skipped = null,
        )

    /**
     * Returns a failure as Device Run writes it in job reports: without attributes, and with the trace twice.
     */
    private fun jobReportFailure(trace: String): JUnitOutcomeElement = JUnitOutcomeElement(null, null, "$trace\n\n$trace\n")

    private companion object {
        private const val FIRST_TRACE = "java.lang.AssertionError: Fails on the first attempt\n\tat a.FlakyTest.flakes(FlakyTest.kt:12)"
        private const val SECOND_TRACE = "java.lang.AssertionError: Fails on the second attempt\n\tat a.FlakyTest.flakes(FlakyTest.kt:12)"
    }
}
