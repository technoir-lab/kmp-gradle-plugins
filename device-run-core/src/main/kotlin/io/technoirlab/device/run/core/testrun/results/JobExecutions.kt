package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.ExecutionReport
import com.google.api.services.devicerun.v1alpha.model.Session
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.io.path.name

/**
 * The executions of the job in [jobDirectory], its shards and their retry attempts, such as `execution-000`, `execution-001`, and
 * `execution-000-rerun-000`.
 *
 * The final session report can omit executions, and Device Run can leave an execution without outputs, so the executions are
 * those that either the report lists or the mirrored outputs contain. They also provide the device log and the start time of each
 * test that exactly one execution ran. When the report of an execution with outputs is missing or malformed, which executions ran a
 * test is unknown, so device logs stay attached to the suite only and tests take the start time of the session. Executions are
 * never ordered by name.
 */
internal class JobExecutions(
    session: Session,
    artifacts: List<MirroredArtifact>,
    private val jobDirectory: String,
) {
    private val logger = LoggerFactory.getLogger(JobExecutions::class.java)
    private val reportParser = JUnitReportParser()
    private val logParser = InstrumentationLogParser()
    private val sessionStartTime = instant(session.sessionReport?.startTime)
    private val executionByTest: Map<String, JobExecution>

    /**
     * The executions of the job.
     */
    val executions: List<JobExecution>

    init {
        val outputsByExecution = artifacts
            .filter { it.relativePath.startsWith("$jobDirectory/") }
            .groupBy { it.relativePath.removePrefix("$jobDirectory/").substringBefore('/', missingDelimiterValue = "") }
            .filterKeys { it.isNotEmpty() }
        // The final report can omit job reports, and with them the executions.
        val entriesByExecution = session.sessionReport?.jobReports.orEmpty()
            .filter { it.displayName == jobDirectory }
            .flatMap { it.executionReports.orEmpty() }
            .filter { it.displayName != null }
            .associateBy { it.displayName }
        executions = (outputsByExecution.keys + entriesByExecution.keys).map { name ->
            execution(name, entriesByExecution[name], outputsByExecution[name].orEmpty())
        }
        val mirroredExecutions = executions.filter { it.name in outputsByExecution }
        executionByTest = if (mirroredExecutions.any { it.report == null }) {
            emptyMap()
        } else {
            mirroredExecutions
                .flatMap { execution -> execution.report?.testCases.orEmpty().map { it.key }.distinct().map { it to execution } }
                .groupBy({ it.first }, { it.second })
                .filterValues { it.size == 1 }
                .mapValues { (_, testExecutions) -> testExecutions.single() }
        }
    }

    /**
     * Returns the device log among [artifacts] of the only execution that ran [outcome], if it was downloaded.
     */
    fun logsOf(outcome: TestOutcome, artifacts: List<MirroredArtifact>): List<ResultArtifact> {
        val execution = executionByTest[outcome.key] ?: return emptyList()
        val logcat = artifacts.find { it.relativePath == "$jobDirectory/${execution.name}/${JobExecution.LOGCAT}" } ?: return emptyList()
        return listOf(ResultArtifact("logcat", logcat.path, "text/plain"))
    }

    /**
     * Returns when the only execution that ran [outcome] started, or else when the session started.
     */
    fun startTimeOf(outcome: TestOutcome): Instant? =
        executionByTest[outcome.key]?.reportEntry?.startTime?.let(::instant) ?: sessionStartTime

    private fun execution(name: String, reportEntry: ExecutionReport?, outputs: List<MirroredArtifact>): JobExecution {
        val files = outputs.filter { it.relativePath == "$jobDirectory/$name/${it.path.name}" }
        return JobExecution(
            name = name,
            shard = SHARD_RETRY.matchEntire(name)?.groupValues?.get(1) ?: name,
            reportEntry = reportEntry,
            report = files.find { it.path.name in JobExecution.REPORT_NAMES }?.let(::parseReport),
            instrumentationLog = lazy { files.find { it.path.name == JobExecution.INSTRUMENT_LOG }?.let { logParser.parse(it.path) } },
        )
    }

    private fun parseReport(executionReport: MirroredArtifact): JUnitReport? = try {
        reportParser.parse(executionReport.path)
    } catch (e: MalformedReportException) {
        logger.warn("Unable to read the Device Run execution report {}: {}", executionReport.relativePath, e.message)
        null
    }

    /**
     * Returns the instant of an RFC 3339 [timestamp] of Device Run, or null when it is missing or malformed.
     */
    private fun instant(timestamp: String?): Instant? = try {
        timestamp?.let(Instant::parse)
    } catch (_: DateTimeParseException) {
        null
    }

    private companion object {
        private val SHARD_RETRY = Regex("(.+)-rerun-\\d+")
    }
}
