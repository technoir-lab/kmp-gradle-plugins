package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
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
     * Writes the AGP results of [session] and returns whether its tests passed.
     */
    fun convert(session: Session, artifacts: List<MirroredArtifact>, context: ResultContext): Boolean {
        val executions = JobExecutions(session, artifacts, context.jobDirectory)
        val verdict = evaluate(session, artifacts, executions, context)
        writeResults(
            session = session,
            artifacts = artifacts,
            executions = executions,
            context = context,
            verdict = verdict,
        )
        report(session, verdict, context.deviceName)
        return verdict.status == TestStatus.PASSED
    }

    private fun evaluate(session: Session, artifacts: List<MirroredArtifact>, executions: JobExecutions, context: ResultContext): Verdict {
        val resultType = session.sessionReport?.result?.resultType
        val jobReport = artifacts.find { it.relativePath == context.jobReportPath }?.let { parseJobReport(it) }
        val outcomes = jobReport?.getOrNull()?.testCases?.map(outcomeMapper::map)
        val failures = remoteFailures(session, resultType) +
            reportFailures(resultType, context.jobReportPath, jobReport, outcomes) +
            evidenceFailures(resultType, jobReport, executions)
        val review = reviewFailedSession(
            session = session,
            resultType = resultType,
            outcomes = outcomes,
            platformFailures = failures,
            executions = executions,
            deviceName = context.deviceName,
        )
        val platformFailures = failures + (review as? FailedSessionReview.Review.Unexplained)?.failures.orEmpty()
        return Verdict(
            resultType = resultType,
            jobReportPath = context.jobReportPath,
            outcomes = outcomes,
            time = jobReport?.getOrNull()?.time,
            platformFailures = platformFailures,
            warnings = listOfNotNull((review as? FailedSessionReview.Review.TestsPass)?.warning),
            status = if (review is FailedSessionReview.Review.TestsPass) {
                TestStatus.PASSED
            } else {
                suiteStatus(resultType, platformFailures, outcomes.orEmpty())
            },
        )
    }

    /**
     * Writes `device-info.pb`, the JUnit report when the job report could be read, and `test-result.pb`.
     */
    private fun writeResults(
        session: Session,
        artifacts: List<MirroredArtifact>,
        executions: JobExecutions,
        context: ResultContext,
        verdict: Verdict,
    ) {
        val deviceInfoFile = context.resultsDirectory / DEVICE_INFO_FILE
        deviceInfoWriter.write(deviceInfoFile, context.deviceName, context.device)
        if (verdict.outcomes != null) {
            val reportFile = context.resultsDirectory / "TEST-${context.deviceName}.xml"
            reportWriter.write(reportFile, verdict.outcomes, context.reportProperties, verdict.time)
        }
        val suiteResult = suiteResultFactory.create(
            suiteName = context.deviceName,
            deviceInfo = deviceInfoFile,
            status = verdict.status,
            tests = verdict.outcomes.orEmpty().map { ExecutedTest(it, executions.startTimeOf(it), executions.logsOf(it)) },
            artifacts = artifacts.map { resultArtifact(it, verdict.jobReportPath) },
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
        if (resultType == PASSED) {
            // Failed attempts of a recovered session remain evidence and do not affect its result.
            return emptyList()
        }
        val executionFailures = if (resultType == CANCELLED) emptyList() else executionFailures(session)
        if (executionFailures.isNotEmpty() || resultType == FAILED) {
            return executionFailures
        }
        val summary = session.sessionReport?.result?.cause?.summary
        val outcome = "The session ended with ${resultType ?: "no result"}"
        return listOf(
            PlatformFailure(
                reason = summary?.reason ?: resultType ?: UNSPECIFIED,
                classification = resultType ?: UNSPECIFIED,
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
        resultType: String?,
        jobReportPath: String,
        jobReport: Result<JUnitReport>?,
        outcomes: List<TestOutcome>?,
    ): List<PlatformFailure> = when {
        resultType != PASSED && resultType != FAILED -> emptyList()
        jobReport == null -> listOf(PlatformFailure(MISSING_REPORT, REPORTING, "Device Run wrote no job report $jobReportPath."))
        outcomes == null -> listOf(PlatformFailure(MALFORMED_REPORT, REPORTING, jobReport.exceptionOrNull()?.message.orEmpty()))
        outcomes.isEmpty() -> listOf(PlatformFailure(NO_TESTS, REPORTING, "The job report $jobReportPath lists no tests."))
        else -> emptyList()
    }

    /**
     * Returns the platform failures that the instrumentation logs of the executions show for a session whose tests ran to
     * completion. Without a job report, the missing report explains the failure.
     */
    private fun evidenceFailures(resultType: String?, jobReport: Result<JUnitReport>?, executions: JobExecutions): List<PlatformFailure> =
        if ((resultType == PASSED || resultType == FAILED) && jobReport != null) {
            instrumentationEvidence.failures(executions.executions)
        } else {
            emptyList()
        }

    /**
     * Reviews a session that Device Run failed although its job report lists tests that all passed and no platform failure explains
     * the failure, or returns null for any other session.
     */
    private fun reviewFailedSession(
        session: Session,
        resultType: String?,
        outcomes: List<TestOutcome>?,
        platformFailures: List<PlatformFailure>,
        executions: JobExecutions,
        deviceName: String,
    ): FailedSessionReview.Review? {
        val listsOnlyPassingTests = !outcomes.isNullOrEmpty() && outcomes.none { it.status.isFailure }
        return if (resultType == FAILED && listsOnlyPassingTests && platformFailures.isEmpty()) {
            failedSessionReview.review(session, executions.executions, deviceName)
        } else {
            null
        }
    }

    private fun suiteStatus(resultType: String?, platformFailures: List<PlatformFailure>, outcomes: List<TestOutcome>): TestStatus =
        when (resultType) {
            PASSED -> when {
                platformFailures.isNotEmpty() -> TestStatus.ERROR
                outcomes.any { it.status.isFailure } -> TestStatus.FAILED
                else -> TestStatus.PASSED
            }
            FAILED -> TestStatus.FAILED
            "ERROR", "TIMED_OUT" -> TestStatus.ERROR
            CANCELLED -> TestStatus.CANCELLED
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
        val testVerdict = when (verdict.status) {
            TestStatus.PASSED -> "passed"
            TestStatus.FAILED -> "failed"
            TestStatus.ERROR -> "ended with an error"
            TestStatus.CANCELLED -> "were cancelled"
            else -> "have no result"
        }
        logger.info(
            "Device Run session {} finished with {}, and the tests {}: {} tests, {} passed ({} flaky), {} failed, {} errors, {} skipped.",
            session.name,
            verdict.resultType ?: "no result",
            testVerdict,
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
     * Logs one warning that names the [flakyTests] of [deviceName], at most [MAX_NAMED_FLAKY_TESTS] of them, with the number of
     * attempts that each failed.
     */
    private fun reportFlakyTests(flakyTests: List<TestOutcome>, deviceName: String) {
        if (flakyTests.isEmpty()) {
            return
        }
        val tests = if (flakyTests.size == 1) "1 test of $deviceName is" else "${flakyTests.size} tests of $deviceName are"
        val named = flakyTests.take(MAX_NAMED_FLAKY_TESTS).map { test ->
            val failedAttempts = test.failedAttempts.size
            "${test.key} (${if (failedAttempts == 1) "1 failed attempt" else "$failedAttempts failed attempts"})"
        }
        val unnamed = flakyTests.size - named.size
        val more = if (unnamed > 0) ", and $unnamed more" else ""
        logger.warn("{} flaky, failing in some attempts and passing in others: {}{}.", tests, named.joinToString(), more)
    }

    /**
     * The verdict of a finished session, before it is written as results.
     *
     * @property resultType the result of the session according to Device Run, which [status] can override.
     * @property outcomes the outcomes of the job report, or null when the report is missing or malformed.
     * @property time the duration of the suite in seconds, as the job report states it.
     * @property warnings the warnings about the verdict, such as one that explains why the tests of a failed session pass.
     */
    private data class Verdict(
        val resultType: String?,
        val jobReportPath: String,
        val outcomes: List<TestOutcome>?,
        val time: String?,
        val platformFailures: List<PlatformFailure>,
        val warnings: List<ReportWarning>,
        val status: TestStatus,
    )

    private companion object {
        private const val PASSED = "PASSED"
        private const val FAILED = "FAILED"
        private const val CANCELLED = "CANCELLED"
        private const val UNSPECIFIED = "RESULT_UNSPECIFIED"
        private const val REPORTING = "REPORTING"
        private const val MISSING_REPORT = "MISSING_JOB_REPORT"
        private const val MALFORMED_REPORT = "MALFORMED_JOB_REPORT"
        private const val NO_TESTS = "NO_TESTS"
        private const val DEVICE_INFO_FILE = "device-info.pb"
        private const val TEST_RESULT_FILE = "test-result.pb"
        private const val MAX_NAMED_FLAKY_TESTS = 10
        private val PLATFORM_RESULTS = setOf("ERROR", "TIMED_OUT", CANCELLED)
    }
}
