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
 * @property reason a constant such as `PLATFORM_INFRASTRUCTURE_ERROR`.
 * @property classification the responsible party or result, such as `INFRA` or `TIMED_OUT`.
 */
internal data class PlatformFailure(
    val reason: String,
    val classification: String,
    val message: String,
)

/**
 * A non-fatal warning reported by Device Run.
 */
internal data class ReportWarning(
    val reason: String,
    val message: String,
)
