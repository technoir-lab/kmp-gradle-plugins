package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import io.technoirlab.device.run.core.testrun.displayCount
import org.slf4j.LoggerFactory
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.outputStream

/**
 * Converts a finished Device Run session and its mirrored artifacts into the results that AGP and Android Studio read:
 * `TEST-<device>.xml`, `test-result.pb`, and `device-info.pb` in the results directory.
 *
 * The job report `<job>/junit.xml`, found by its path, provides the final outcome of each test, counting a test that passed in some
 * attempt as passed; execution reports and instrumentation logs only serve as evidence, so tests are never counted twice. The
 * verdict follows the session result, which is available even when the final report omits job reports, with one exception: Device
 * Run fails a shard when no attempt passes every test, so a failed session whose job report lists only passing tests passes when
 * [FailedSessionReview] confirms that failed attempts alone failed it. A run passes only when its job report lists passing tests
 * and the instrumentation logs show neither tests that never ran nor failures that the reports omit.
 *
 * The verdict does not depend on the device logs of the executions, so it can decide whether they are downloaded before the results
 * are written.
 */
internal class DeviceRunResultConverter {
    private val logger = LoggerFactory.getLogger(DeviceRunResultConverter::class.java)

    private val reportParser = JUnitReportParser()
    private val outcomeMapper = TestOutcomeMapper()
    private val instrumentationEvidence = InstrumentationEvidence()
    private val failedSessionReview = FailedSessionReview()
    private val reportWriter = TestReportWriter()
    private val deviceInfoWriter = DeviceInfoWriter()
    private val suiteResultFactory = TestSuiteResultFactory()

    /**
     * Decides whether the tests of [session] passed, from its report and its mirrored [artifacts], which need not include the
     * device logs of the executions.
     */
    fun evaluate(session: Session, artifacts: List<MirroredArtifact>, context: ResultContext): Verdict {
        val executions = JobExecutions(session, artifacts, context.jobDirectory)
        val resultType = session.sessionReport?.result?.resultType
        val testsRan = session.testsRanToCompletion()
        val jobReport = artifacts.find { it.relativePath == context.jobReportPath }?.let { parseJobReport(it) }
        val report = jobReport?.getOrNull()
        val outcomes = report?.testCases?.map(outcomeMapper::map)
        val evidence = examineEvidence(testsRan, jobReport, executions)
        val failures = remoteFailures(session, resultType) +
            reportFailures(testsRan, context.jobReportPath, jobReport, outcomes) +
            evidence.failures
        val verdict = Verdict(
            outcomes = outcomes,
            time = report?.time,
            executions = executions,
            platformFailures = failures,
            warnings = evidence.warnings,
            status = suiteStatus(resultType, failures, outcomes.orEmpty()),
        )
        val review = failedSessionReview.review(
            session = session,
            outcomes = outcomes,
            platformFailures = failures,
            executions = executions.executions,
            deviceName = context.deviceName,
        )
        return when (review) {
            null -> verdict
            is FailedSessionReview.Review.TestsPass ->
                verdict.copy(status = TestStatus.PASSED, warnings = verdict.warnings + review.warning)
            // The verdict keeps the failure of the session, which the unexplained failures explain.
            is FailedSessionReview.Review.Unexplained -> verdict.copy(platformFailures = verdict.platformFailures + review.failures)
        }
    }

    /**
     * Writes the AGP results of [session] with its [verdict] and mirrored [artifacts], and logs the verdict.
     */
    fun write(session: Session, artifacts: List<MirroredArtifact>, context: ResultContext, verdict: Verdict) {
        writeResults(session, artifacts, context, verdict)
        report(session, verdict, context.deviceName)
    }

    /**
     * Writes `device-info.pb`, the JUnit report when the job report could be read, and `test-result.pb`.
     */
    private fun writeResults(session: Session, artifacts: List<MirroredArtifact>, context: ResultContext, verdict: Verdict) {
        val deviceInfoFile = context.resultsDirectory / DEVICE_INFO_FILE
        deviceInfoWriter.write(deviceInfoFile, context.deviceName, context.device)
        if (verdict.outcomes != null) {
            val reportFile = context.resultsDirectory / "TEST-${context.deviceName}.xml"
            reportWriter.write(reportFile, verdict.outcomes, context.reportProperties, verdict.time)
        }
        val deviceLogs = verdict.executions.deviceLogs(artifacts)
        val suiteResult = suiteResultFactory.create(
            suiteName = context.deviceName,
            deviceInfo = deviceInfoFile,
            status = verdict.status,
            tests = verdict.outcomes.orEmpty().map { outcome ->
                val deviceLog = deviceLogs[outcome.key]?.let { resultArtifact(it, context.jobReportPath) }
                ExecutedTest(outcome, verdict.executions.startTimeOf(outcome), listOfNotNull(deviceLog))
            },
            artifacts = artifacts.map { resultArtifact(it, context.jobReportPath) },
            platformFailures = verdict.platformFailures,
            warnings = verdict.warnings + warnings(session),
        )
        (context.resultsDirectory / TEST_RESULT_FILE).outputStream().use { suiteResult.writeTo(it) }
    }

    private fun parseJobReport(jobReport: MirroredArtifact): Result<JUnitReport> = try {
        Result.success(reportParser.parse(jobReport.path))
    } catch (e: MalformedReportException) {
        Result.failure(e)
    }

    /**
     * Returns the platform failures that the session report explains, preferring the causes of failed executions over the
     * generic session cause. Failed tests are not platform failures.
     *
     * A cancelled session is explained by its own cause, because cancellation aborts running executions, which can then report
     * infrastructure errors.
     */
    private fun remoteFailures(session: Session, resultType: String?): List<PlatformFailure> {
        if (resultType == ResultType.PASSED) {
            // Failed attempts of a recovered session remain evidence and do not affect its result.
            return emptyList()
        }
        val executionFailures = if (resultType == ResultType.CANCELLED) emptyList() else executionFailures(session)
        if (executionFailures.isNotEmpty() || resultType == ResultType.FAILED) {
            return executionFailures
        }
        val summary = session.sessionReport?.result?.cause?.summary
        val outcome = "The session ended with ${resultType ?: "no result"}"
        return listOf(
            PlatformFailure(
                reason = summary?.reason ?: resultType ?: ResultType.UNSPECIFIED,
                classification = resultType ?: ResultType.UNSPECIFIED,
                message = summary?.message?.let { "$outcome: $it" } ?: "$outcome.",
            ),
        )
    }

    private fun executionFailures(session: Session): List<PlatformFailure> = session.sessionReport?.jobReports.orEmpty().flatMap { job ->
        job.executionReports.orEmpty()
            .filter { it.result?.resultType in PLATFORM_RESULTS }
            .map { execution ->
                val executionResult = execution.result.resultType
                val summary = execution.result.cause?.summary
                val outcome = "Execution ${execution.displayName} of job ${job.displayName} ended with $executionResult"
                PlatformFailure(
                    reason = summary?.reason ?: executionResult,
                    classification = summary?.type ?: executionResult,
                    message = summary?.message?.let { "$outcome: $it" } ?: "$outcome.",
                )
            }
    }

    /**
     * Returns the platform failures of the job report of a session whose tests ran to completion.
     */
    private fun reportFailures(
        testsRan: Boolean,
        jobReportPath: String,
        jobReport: Result<JUnitReport>?,
        outcomes: List<TestOutcome>?,
    ): List<PlatformFailure> = when {
        !testsRan -> emptyList()
        jobReport == null -> listOf(PlatformFailure(RunnerFailure.MISSING_JOB_REPORT, "Device Run wrote no job report $jobReportPath."))
        outcomes == null -> listOf(PlatformFailure(RunnerFailure.MALFORMED_JOB_REPORT, jobReport.exceptionOrNull()?.message.orEmpty()))
        outcomes.isEmpty() -> listOf(PlatformFailure(RunnerFailure.NO_TESTS, "The job report $jobReportPath lists no tests."))
        else -> emptyList()
    }

    /**
     * Returns what the instrumentation logs of the executions show for a session whose tests ran to completion. Without a job report,
     * the missing report explains the failure.
     */
    private fun examineEvidence(
        testsRan: Boolean,
        jobReport: Result<JUnitReport>?,
        executions: JobExecutions,
    ): InstrumentationEvidence.Findings = if (testsRan && jobReport != null) {
        instrumentationEvidence.examine(executions.executions)
    } else {
        InstrumentationEvidence.Findings(failures = emptyList(), warnings = emptyList())
    }

    private fun suiteStatus(resultType: String?, platformFailures: List<PlatformFailure>, outcomes: List<TestOutcome>): TestStatus =
        when (resultType) {
            ResultType.PASSED -> when {
                platformFailures.isNotEmpty() -> TestStatus.ERROR
                outcomes.any { it.status.isFailure } -> TestStatus.FAILED
                else -> TestStatus.PASSED
            }
            ResultType.FAILED -> TestStatus.FAILED
            ResultType.ERROR, ResultType.TIMED_OUT -> TestStatus.ERROR
            ResultType.CANCELLED -> TestStatus.CANCELLED
            else -> TestStatus.TEST_STATUS_UNSPECIFIED
        }

    private fun warnings(session: Session): List<ReportWarning> {
        val jobReports = session.sessionReport?.jobReports.orEmpty()
        val executionReports = jobReports.flatMap { it.executionReports.orEmpty() }
        val warnings = jobReports.flatMap { it.warnings.orEmpty() } + executionReports.flatMap { it.warnings.orEmpty() }
        return warnings.mapNotNull { it.summary }.map { ReportWarning(it.reason.orEmpty(), it.message.orEmpty()) }
    }

    private fun resultArtifact(artifact: MirroredArtifact, jobReportPath: String): ResultArtifact {
        val name = artifact.path.name
        val label = when {
            artifact.relativePath == jobReportPath -> "deviceRun.junit"
            "/artifacts/" in artifact.relativePath -> "deviceRun.pulledFile"
            name == JobExecution.LOGCAT -> "logcat"
            name in JobExecution.REPORT_NAMES -> "deviceRun.testResult"
            name == JobExecution.INSTRUMENT_LOG -> "deviceRun.instrumentLog"
            name.startsWith("video") -> "deviceRun.video"
            else -> "deviceRun.artifact"
        }
        val mimeType = when (name.substringAfterLast('.')) {
            "txt", "log" -> "text/plain"
            "xml" -> "application/xml"
            "webm" -> "video/webm"
            "mp4" -> "video/mp4"
            else -> null
        }
        return ResultArtifact(label, artifact.path, mimeType)
    }

    /**
     * Logs the result of the session next to the verdict on the tests of [deviceName], which can differ, the flaky tests, and what
     * explains the verdict.
     */
    private fun report(session: Session, verdict: Verdict, deviceName: String) {
        val outcomes = verdict.outcomes.orEmpty()
        fun count(status: TestOutcome.Status) = outcomes.count { it.status == status }
        logger.info(
            "Device Run session {} finished with {}, and the tests {}: {} tests, {} passed ({} flaky), {} failed, {} errors, {} skipped.",
            session.name,
            session.sessionReport?.result?.resultType ?: "no result",
            verdict.status.verdictPhrase,
            outcomes.size,
            count(TestOutcome.Status.PASSED),
            outcomes.count { it.flaky },
            count(TestOutcome.Status.FAILED),
            count(TestOutcome.Status.ERROR),
            count(TestOutcome.Status.SKIPPED),
        )
        reportFlakyTests(outcomes.filter { it.flaky }, deviceName)
        verdict.warnings.forEach { logger.warn(it.message) }
        if (verdict.status != TestStatus.PASSED) {
            verdict.platformFailures.forEach { logger.error("Device Run {} {}: {}", it.classification, it.reason, it.message) }
        }
    }

    /**
     * The verdict on tests of this status, phrased to follow "the tests", such as "passed".
     */
    private val TestStatus.verdictPhrase: String
        get() = when (this) {
            TestStatus.PASSED -> "passed"
            TestStatus.FAILED -> "failed"
            TestStatus.ERROR -> "ended with an error"
            TestStatus.CANCELLED -> "were cancelled"
            else -> "have no result"
        }

    /**
     * Logs one warning that names the [flakyTests] of [deviceName], at most [MAX_NAMED_FLAKY_TESTS] of them, with the number of
     * attempts that each failed.
     */
    private fun reportFlakyTests(flakyTests: List<TestOutcome>, deviceName: String) {
        if (flakyTests.isEmpty()) {
            return
        }
        val tests = if (flakyTests.size == 1) "1 test of $deviceName is" else "${flakyTests.size} tests of $deviceName are"
        val named = flakyTests.take(MAX_NAMED_FLAKY_TESTS).map { "${it.key} (${displayCount(it.failedAttempts.size, "failed attempt")})" }
        val unnamed = flakyTests.size - named.size
        val more = if (unnamed > 0) ", and $unnamed more" else ""
        logger.warn("{} flaky, failing in some attempts and passing in others: {}{}.", tests, named.joinToString(), more)
    }

    /**
     * The verdict of a finished session, before it is written as results.
     *
     * @property outcomes the outcomes of the job report, or null when the report is missing or malformed.
     * @property time the duration of the suite in seconds, as the job report states it.
     * @property executions the executions of the job, which ran the tests.
     * @property warnings the warnings about the verdict, such as one that explains why the tests of a failed session pass.
     */
    data class Verdict(
        val outcomes: List<TestOutcome>?,
        val time: String?,
        val executions: JobExecutions,
        val platformFailures: List<PlatformFailure>,
        val warnings: List<ReportWarning>,
        val status: TestStatus,
    ) {
        /**
         * Whether the tests passed.
         */
        val passed: Boolean
            get() = status == TestStatus.PASSED
    }

    private companion object {
        private const val DEVICE_INFO_FILE = "device-info.pb"
        private const val TEST_RESULT_FILE = "test-result.pb"
        private const val MAX_NAMED_FLAKY_TESTS = 10
        private val PLATFORM_RESULTS = setOf(ResultType.ERROR, ResultType.TIMED_OUT, ResultType.CANCELLED)
    }
}
