package io.technoirlab.device.run.core.testrun.results

/**
 * The final outcome of one test.
 *
 * @property className the fully qualified class name.
 * @property name the test name, including parameters such as `[0]`.
 * @property time the duration in seconds, as reported by Device Run.
 * @property detail the failure, error, or skip explanation, when there is one; the failure of the first failed attempt of a failed
 * test.
 * @property failedAttempts the failure of each failed attempt, in attempt order.
 */
internal data class TestOutcome(
    val className: String,
    val name: String,
    val time: String?,
    val status: Status,
    val detail: Detail?,
    val failedAttempts: List<Detail>,
) {
    /**
     * The key that identifies the test across reports, `<class name>#<test name>`.
     */
    val key: String
        get() = testKey(className, name)

    /**
     * Whether the test passed in some attempts and failed in others, in any order.
     */
    val flaky: Boolean
        get() = status == Status.PASSED && failedAttempts.isNotEmpty()

    /**
     * The final status of a test.
     */
    enum class Status {
        PASSED,
        FAILED,
        ERROR,
        SKIPPED,
        ;

        /**
         * Whether the test failed or ended with an error.
         */
        val isFailure: Boolean
            get() = this == FAILED || this == ERROR
    }

    /**
     * Explains a failed, erroneous, or skipped test.
     *
     * @property type the exception type, when known.
     * @property trace the complete trace or explanation.
     */
    data class Detail(
        val type: String?,
        val message: String,
        val trace: String,
    )
}
