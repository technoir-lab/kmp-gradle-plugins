package io.technoirlab.device.run.core.testrun.results

/**
 * Turns the test cases of a Device Run job report into final test outcomes.
 *
 * The job report has one test case for each test, with a `failure` for each failed attempt, in attempt order. A test that passed in
 * some attempts and failed in others, in any order, is marked `flaky="true"`; such a test passed. The rule covers only failures, the
 * combination Device Run was observed to produce: an `error` marked flaky was never observed, and it fails the test.
 */
internal class TestOutcomeMapper {
    fun map(testCase: JUnitTestCase): TestOutcome {
        val error = testCase.error
        val failures = testCase.failures
        val skipped = testCase.skipped
        val (status, element) = when {
            error != null -> TestOutcome.Status.ERROR to error
            failures.isNotEmpty() && !testCase.flaky -> TestOutcome.Status.FAILED to failures.first()
            failures.isNotEmpty() -> TestOutcome.Status.PASSED to null
            skipped != null -> TestOutcome.Status.SKIPPED to skipped
            else -> TestOutcome.Status.PASSED to null
        }
        return TestOutcome(
            className = testCase.className,
            name = testCase.name,
            time = testCase.time,
            status = status,
            detail = element?.let(::detail),
            failedAttempts = failures.map { failure ->
                detail(failure) ?: TestOutcome.Detail(type = failure.type, message = "", trace = "")
            },
        )
    }

    private fun detail(element: JUnitOutcomeElement): TestOutcome.Detail? {
        val trace = withoutRepetition(element.text.trim())
        if (trace.isEmpty() && element.message == null) {
            return null
        }
        // Device Run omits the message and type attributes, so the first line of the trace, `<type>: <message>`, provides them.
        val firstLine = trace.lineSequence().first()
        val typeCandidate = firstLine.substringBefore(": ")
        val hasType = TYPE_NAME.matches(typeCandidate)
        return TestOutcome.Detail(
            type = element.type ?: typeCandidate.takeIf { hasType },
            message = element.message ?: if (hasType) firstLine.substringAfter(": ", missingDelimiterValue = "") else firstLine,
            trace = trace,
        )
    }

    /**
     * Returns the first half of [trace] when it consists of the same text twice, separated by a blank line, as in Device Run job
     * reports; any other trace is returned unchanged.
     */
    private fun withoutRepetition(trace: String): String {
        var separator = trace.indexOf(BLANK_LINE)
        while (separator >= 0) {
            val firstHalf = trace.substring(0, separator)
            if (firstHalf == trace.substring(separator + BLANK_LINE.length)) {
                return firstHalf
            }
            separator = trace.indexOf(BLANK_LINE, separator + 1)
        }
        return trace
    }

    private companion object {
        private const val BLANK_LINE = "\n\n"
        private val TYPE_NAME = Regex("""[\p{L}_$][\p{L}\p{N}_$]*(\.[\p{L}_$][\p{L}\p{N}_$]*)*""")
    }
}
