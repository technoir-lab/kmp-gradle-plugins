package io.technoirlab.device.run.core.testrun.results

/**
 * The test cases of a JUnit XML report.
 *
 * @property time the duration of the suite in seconds, as reported.
 */
internal data class JUnitReport(
    val testCases: List<JUnitTestCase>,
    val time: String?,
)

/**
 * A `testcase` element of a JUnit XML report.
 *
 * @property className the fully qualified class name from the `classname` attribute.
 * @property time the duration in seconds, as reported.
 * @property flaky whether Device Run marked the test as flaky because it passed in some attempts and failed in others.
 * @property failure the first `failure` element; job reports have one for each failed attempt.
 */
internal data class JUnitTestCase(
    val className: String,
    val name: String,
    val time: String?,
    val flaky: Boolean,
    val failure: JUnitOutcomeElement?,
    val error: JUnitOutcomeElement?,
    val skipped: JUnitOutcomeElement?,
) {
    /**
     * The key that identifies the test across reports, `<class name>#<test name>`.
     */
    val key: String
        get() = testKey(className, name)
}

/**
 * Returns the key that identifies a test across reports, `<class name>#<test name>`.
 */
internal fun testKey(className: String, name: String): String = "$className#$name"

/**
 * A `failure`, `error`, or `skipped` element of a test case.
 */
internal data class JUnitOutcomeElement(
    val message: String?,
    val type: String?,
    val text: String,
)
