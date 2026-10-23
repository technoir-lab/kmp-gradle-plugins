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
 * The session result is the verdict authority, because it is available even when the final report omits job reports. The job
 * report `<job>/junit.xml`, found by its path, provides the final outcome of each test; execution reports and instrumentation logs
 * only serve as evidence, so tests are never counted twice. A run passes only when the session passed, its job report lists passing
 * tests, and the instrumentation logs show neither tests that never ran nor failures that the reports omit.
 */
internal class DeviceRunResultConverter {
    private val logger = LoggerFactory.getLogger(DeviceRunResultConverter::class.java)

    private val reportParser = JUnitReportParser()
    private val outcomeMapper = TestOutcomeMapper()
    private val instrumentationEvidence = InstrumentationEvidence()
    private val reportWriter = TestReportWriter()
    private val deviceInfoWriter = DeviceInfoWriter()
    private val suiteResultFactory = TestSuiteResultFactory()

    /**
     * Writes the AGP results of [session] and returns whether its tests passed.
     */
    fun convert(session: Session, artifacts: List<MirroredArtifact>, context: ResultContext): Boolean {
        val executions = JobExecutions(session, artifacts, context.jobDirectory)
        val verdict = evaluate(session, artifacts, executions, context.jobReportPath)
        writeResults(
            session = session,
            artifacts = artifacts,
            executions = executions,
            context = context,
            verdict = verdict,
        )
        report(session, verdict)
        return verdict.status == TestStatus.PASSED
    }

    private fun evaluate(session: Session, artifacts: List<MirroredArtifact>, executions: JobExecutions, jobReportPath: String): Verdict {
        val resultType = session.sessionReport?.result?.resultType
        val jobReport = artifacts.find { it.relativePath == jobReportPath }?.let { parseJobReport(it) }
        val outcomes = jobReport?.getOrNull()?.testCases?.map(outcomeMapper::map)
        val platformFailures = remoteFailures(session, resultType) +
            reportFailures(resultType, jobReportPath, jobReport, outcomes) +
            evidenceFailures(resultType, jobReport, executions)
        return Verdict(
            resultType = resultType,
            jobReportPath = jobReportPath,
            outcomes = outcomes,
            time = jobReport?.getOrNull()?.time,
            platformFailures = platformFailures,
            status = suiteStatus(resultType, platformFailures, outcomes.orEmpty()),
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
            warnings = warnings(session),
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

    private fun report(session: Session, verdict: Verdict) {
        val outcomes = verdict.outcomes.orEmpty()
        fun count(status: TestOutcome.Status) = outcomes.count { it.status == status }
        logger.info(
            "Device Run session {} finished with {}: {} tests, {} passed ({} after a retry), {} failed, {} errors, {} skipped.",
            session.name,
            verdict.resultType ?: "no result",
            outcomes.size,
            count(TestOutcome.Status.PASSED),
            outcomes.count { it.recovered },
            count(TestOutcome.Status.FAILED),
            count(TestOutcome.Status.ERROR),
            count(TestOutcome.Status.SKIPPED),
        )
        if (verdict.status != TestStatus.PASSED) {
            verdict.platformFailures.forEach { logger.error("Device Run {} {}: {}", it.classification, it.reason, it.message) }
        }
    }

    /**
     * The verdict of a finished session, before it is written as results.
     *
     * @property outcomes the outcomes of the job report, or null when the report is missing or malformed.
     * @property time the duration of the suite in seconds, as the job report states it.
     */
    private data class Verdict(
        val resultType: String?,
        val jobReportPath: String,
        val outcomes: List<TestOutcome>?,
        val time: String?,
        val platformFailures: List<PlatformFailure>,
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
        private val PLATFORM_RESULTS = setOf("ERROR", "TIMED_OUT", CANCELLED)
    }
}
