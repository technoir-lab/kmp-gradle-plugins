package io.technoirlab.device.run.core.testrun.results

import java.nio.file.Path
import java.time.Instant

/**
 * A file attached to test results.
 */
internal data class ResultArtifact(
    val label: String,
    val path: Path,
    val mimeType: String?,
)

/**
 * A test with its final outcome, when it started, and the files attached to it.
 *
 * @property startTime the estimated start time, or null when it is unknown.
 */
internal data class ExecutedTest(
    val outcome: TestOutcome,
    val startTime: Instant?,
    val artifacts: List<ResultArtifact>,
)

/**
 * A failure of the platform rather than of a test, such as an infrastructure error or a missing report.
 *
 * @property reason a constant such as `PLATFORM_INFRASTRUCTURE_ERROR`, or the name of a [RunnerFailure].
 * @property classification the responsible party or result, such as `INFRA` or `TIMED_OUT`, or that of a [RunnerFailure].
 */
internal data class PlatformFailure(
    val reason: String,
    val classification: String,
    val message: String,
) {
    /**
     * A [failure] that the runner found, which [message] describes.
     */
    constructor(failure: RunnerFailure, message: String) : this(failure.name, failure.classification.name, message)
}

/**
 * A failure that the runner finds in the reports and outputs of a session, which Device Run does not report itself.
 *
 * @property classification what is at fault.
 */
internal enum class RunnerFailure(
    val classification: Classification,
) {
    /**
     * Device Run wrote no job report although the tests ran.
     */
    MISSING_JOB_REPORT(Classification.REPORTING),

    /**
     * The job report cannot be read.
     */
    MALFORMED_JOB_REPORT(Classification.REPORTING),

    /**
     * The job report lists no tests.
     */
    NO_TESTS(Classification.REPORTING),

    /**
     * Device Run failed the session although its job report lists no failed test, and nothing confirms why.
     */
    UNEXPLAINED_SESSION_FAILURE(Classification.REPORTING),

    /**
     * An instrumentation run ended early, and no attempt ran the rest of its tests.
     */
    INCOMPLETE_TEST_RUN(Classification.INSTRUMENTATION),

    /**
     * The summary of an instrumentation run lists failures that the reports omit.
     */
    UNREPORTED_TEST_FAILURES(Classification.INSTRUMENTATION),
    ;

    /**
     * What is at fault in a failure that the runner finds.
     */
    enum class Classification {
        /**
         * The reports of Device Run.
         */
        REPORTING,

        /**
         * An instrumentation run, whose log shows what the reports omit.
         */
        INSTRUMENTATION,
    }
}

/**
 * A non-fatal warning reported by Device Run.
 */
internal data class ReportWarning(
    val reason: String,
    val message: String,
)
