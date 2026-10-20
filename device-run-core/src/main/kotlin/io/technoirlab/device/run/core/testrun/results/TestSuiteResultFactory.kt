package io.technoirlab.device.run.core.testrun.results

import com.google.protobuf.Timestamp
import com.google.testing.platform.proto.api.core.ErrorDetailProto.ErrorDetail
import com.google.testing.platform.proto.api.core.ErrorDetailProto.ErrorSummary
import com.google.testing.platform.proto.api.core.ErrorProto.Error
import com.google.testing.platform.proto.api.core.IssueProto.Issue
import com.google.testing.platform.proto.api.core.LabelProto.Label
import com.google.testing.platform.proto.api.core.PathProto
import com.google.testing.platform.proto.api.core.PlatformErrorProto.PlatformError
import com.google.testing.platform.proto.api.core.TestArtifactProto.Artifact
import com.google.testing.platform.proto.api.core.TestArtifactProto.ArtifactType
import com.google.testing.platform.proto.api.core.TestCaseProto.TestCase
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteMetaData
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Builds the UTP test suite result that Android Studio reads.
 *
 * Device Run reports test durations but no start times, so each test case starts at an estimated time and ends after its reported
 * duration. Android Studio derives durations from both, and lists results to import only when a test case has a start time. A
 * test case without a reported duration has no end time.
 */
internal class TestSuiteResultFactory {
    /**
     * Returns the result of the suite [suiteName], whose [tests] ran on the device that the [deviceInfo] file describes.
     *
     * Android Studio finds the device of each test in the device information attached to it, so [deviceInfo] is attached to every
     * test as well as to the suite.
     */
    fun create(
        suiteName: String,
        deviceInfo: Path,
        status: TestStatus,
        tests: List<ExecutedTest>,
        artifacts: List<ResultArtifact>,
        platformFailures: List<PlatformFailure>,
        warnings: List<ReportWarning>,
    ): TestSuiteResult {
        val deviceInfoArtifact = artifact(ResultArtifact(DEVICE_INFO_LABEL, deviceInfo, null))
        return TestSuiteResult.newBuilder()
            .setTestSuiteMetaData(TestSuiteMetaData.newBuilder().setTestSuiteName(suiteName).setScheduledTestCaseCount(tests.size))
            .setTestStatus(status)
            .addAllTestResult(tests.map { testResult(it, deviceInfoArtifact) })
            .addOutputArtifact(deviceInfoArtifact)
            .addAllOutputArtifact(artifacts.map(::artifact))
            .addAllIssue(warnings.map(::issue))
            .apply {
                if (platformFailures.isNotEmpty()) {
                    setPlatformError(PlatformError.newBuilder().addAllErrors(platformFailures.map(::errorDetail)))
                }
            }
            .build()
    }

    private fun testResult(test: ExecutedTest, deviceInfo: Artifact): TestResult {
        val outcome = test.outcome
        val testCase = TestCase.newBuilder()
            .setTestPackage(outcome.className.substringBeforeLast('.', missingDelimiterValue = ""))
            .setTestClass(outcome.className.substringAfterLast('.'))
            .setTestMethod(outcome.name)
            .apply {
                test.startTime?.let { start ->
                    setStartTime(timestamp(start))
                    duration(outcome.time)?.let { setEndTime(timestamp(start + it)) }
                }
            }
        return TestResult.newBuilder()
            .setTestCase(testCase)
            .setTestStatus(
                when (outcome.status) {
                    TestOutcome.Status.PASSED -> TestStatus.PASSED
                    TestOutcome.Status.FAILED -> TestStatus.FAILED
                    TestOutcome.Status.ERROR -> TestStatus.ERROR
                    TestOutcome.Status.SKIPPED -> TestStatus.SKIPPED
                },
            )
            .apply {
                outcome.detail?.let { detail ->
                    setError(
                        Error.newBuilder()
                            .setErrorType(detail.type.orEmpty())
                            .setErrorMessage(detail.message)
                            .setStackTrace(detail.trace),
                    )
                }
            }
            .addOutputArtifact(deviceInfo)
            .addAllOutputArtifact(test.artifacts.map(::artifact))
            .build()
    }

    /**
     * Returns the duration of a test from its reported [time] in seconds, or null when the time is missing or not a valid duration.
     */
    private fun duration(time: String?): Duration? {
        val nanos = time?.toBigDecimalOrNull()?.movePointRight(NANO_DIGITS)?.toBigInteger() ?: return null
        return if (nanos.signum() >= 0 && nanos.bitLength() < Long.SIZE_BITS) Duration.ofNanos(nanos.toLong()) else null
    }

    private fun timestamp(instant: Instant): Timestamp = Timestamp.newBuilder()
        .setSeconds(instant.epochSecond)
        .setNanos(instant.nano)
        .build()

    private fun artifact(artifact: ResultArtifact): Artifact = Artifact.newBuilder()
        .setLabel(label(artifact.label))
        .setSourcePath(PathProto.Path.newBuilder().setPath(artifact.path.toString()))
        .setType(ArtifactType.TEST_DATA)
        .apply { artifact.mimeType?.let { setMimeType(it) } }
        .build()

    private fun errorDetail(failure: PlatformFailure): ErrorDetail = ErrorDetail.newBuilder()
        .setSummary(
            ErrorSummary.newBuilder()
                .setNamespace(label(RESULT_NAMESPACE))
                .setErrorName(failure.reason)
                .setErrorClassification(failure.classification)
                .setErrorMessage(failure.message),
        )
        .build()

    private fun issue(warning: ReportWarning): Issue = Issue.newBuilder()
        .setNamespace(label(WARNING_NAMESPACE))
        .setSeverity(Issue.Severity.WARNING)
        .setName(warning.reason)
        .setMessage(warning.message)
        .build()

    private fun label(label: String): Label = Label.newBuilder()
        .setLabel(label)
        .setNamespace(ANDROID_NAMESPACE)
        .build()

    private companion object {
        private const val ANDROID_NAMESPACE = "android"
        private const val DEVICE_INFO_LABEL = "device-info"
        private const val RESULT_NAMESPACE = "deviceRun.result"
        private const val WARNING_NAMESPACE = "deviceRun.warning"
        private const val NANO_DIGITS = 9
    }
}
