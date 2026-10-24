package io.technoirlab.device.run.core.testrun.results

import com.google.api.client.json.GenericJson
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionReport
import com.google.api.services.storage.model.Objects
import io.technoirlab.device.run.core.client.FakeCloudTransport
import io.technoirlab.device.run.core.client.deviceRunFixtures
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.testDeviceRunner
import io.technoirlab.device.run.core.testrun.TestRunRequest
import java.nio.file.Path
import java.time.Instant

/**
 * A finished Device Run session of the fixtures, `device-run/sessions/<name>`: the session with its final report, and the outputs
 * that Device Run uploaded below the directory of the session.
 *
 * The fixtures leave out device logs and other large outputs, so the outputs hold a placeholder for each file that the report
 * references, or that the captured listing of the session, `objects.json`, names, but the fixtures lack. A change returns a new
 * scenario and leaves this one as it is.
 *
 * @property session the session as its full view shows it once it is done.
 * @property outputs the contents of the outputs by their paths below the session directory, such as `mediumPhone/junit.xml`.
 * @property outputCreationTimes when Device Run created each output, by its path below the session directory, for the outputs that
 * the captured listing names with a creation time.
 */
class CapturedScenario private constructor(
    private val name: String,
    internal val session: Session,
    internal val outputs: Map<String, String>,
    internal val outputCreationTimes: Map<String, Instant>,
) {
    private val capturedDirectory = sessionDirectory(session)

    /**
     * The job of the session, whose outputs lie in the directory of its name.
     */
    internal val job: String
        get() = session.sessionConfig.jobConfigs.single().displayName

    /**
     * Loads the session [name] of the fixtures, such as `test-failure`.
     */
    constructor(name: String) : this(name, ScenarioFiles(deviceRunFixtures("sessions/$name")))

    private constructor(name: String, files: ScenarioFiles) : this(
        name = name,
        session = files.session,
        outputs = files.outputs,
        outputCreationTimes = files.outputCreationTimes,
    )

    /**
     * Returns the scenario with the job renamed to [job], which moves the outputs of the job to the directory of that name, as for a
     * test run of a device of that name.
     */
    fun withJob(job: String): CapturedScenario {
        val capturedJob = this.job
        val renamed = session.deepCopy().apply {
            sessionConfig.jobConfigs.filter { it.displayName == capturedJob }.forEach { it.displayName = job }
            sessionReport = sessionReport?.withRenamedJob(capturedJob, job)
        }
        return CapturedScenario(
            name = name,
            session = renamed,
            outputs = outputs.mapKeys { (path, _) -> path.movedToJob(capturedJob, job) },
            outputCreationTimes = outputCreationTimes.mapKeys { (path, _) -> path.movedToJob(capturedJob, job) },
        )
    }

    /**
     * Returns the scenario with [content] as the output at [relativePath] below the session directory, in place of the captured
     * output or in addition to the outputs.
     */
    fun withOutput(relativePath: String, content: String): CapturedScenario =
        CapturedScenario(name, session, outputs + (relativePath to content), outputCreationTimes)

    /**
     * Returns the scenario without the output at [relativePath] below the session directory.
     */
    internal fun withoutOutput(relativePath: String): CapturedScenario =
        CapturedScenario(name, session, outputs - relativePath, outputCreationTimes - relativePath)

    /**
     * Returns the scenario with [report] as the final report of the session.
     */
    internal fun withSessionReport(report: SessionReport): CapturedScenario =
        CapturedScenario(name, session.deepCopy().setSessionReport(report.deepCopy()), outputs, outputCreationTimes)

    /**
     * Returns the scenario with the final report of the session changed by [change], which receives a copy of the report.
     */
    internal fun withSessionReport(change: SessionReport.() -> Unit): CapturedScenario =
        CapturedScenario(name, session.deepCopy().apply { sessionReport.change() }, outputs, outputCreationTimes)

    /**
     * Returns the scenario with the final report of the session [scenario] of the fixtures, whose job and referenced outputs move to
     * the job and the session directory of this scenario.
     */
    internal fun withSessionReportOf(scenario: String): CapturedScenario =
        withSessionReport(CapturedScenario(scenario).withJob(job).reportIn(capturedDirectory))

    /**
     * Writes the outputs below [directory] as the runner mirrors them, and returns the mirrored artifacts in the order of their paths.
     */
    internal fun mirrorTo(directory: Path): List<MirroredArtifact> = mirrorOutputs(directory, outputs)

    /**
     * Makes [cloud] finish each session that it creates as the captured session: with its final report, which references the
     * outputs in the directory of the created session, and with its outputs, which Device Run uploads below that directory.
     */
    internal fun uploadTo(cloud: FakeCloudTransport) {
        cloud.sessionOutputs = outputs
        cloud.sessionReport = { sessionDirectory -> reportIn(GcsUri.parse(sessionDirectory)) }
    }

    /**
     * Runs the tests of [request] in a replay of the session, writes their results, and returns whether they passed.
     *
     * The device of the test run needs the name of the job, such as `mediumPhone` for `test-failure`, because the runner reads the
     * outputs of a job from the directory of its name.
     */
    internal fun runTests(request: TestRunRequest): Boolean {
        val cloud = FakeCloudTransport()
        uploadTo(cloud)

        val passed = testDeviceRunner(cloud).use { it.runTests(request) }

        val requestedJob = cloud.sessions.single().sessionConfig.jobConfigs.single().displayName
        check(requestedJob == job) { "The test run ran the job $requestedJob, but the captured session $name ran the job $job" }
        return passed
    }

    /**
     * Returns the final report of the session with the outputs that it references moved to [sessionDirectory].
     */
    private fun reportIn(sessionDirectory: GcsUri): SessionReport =
        session.sessionReport.withOutputFiles { file -> file.relativeTo(capturedDirectory)?.let { sessionDirectory / it } ?: file }

    /**
     * Returns a copy of the report with the job [capturedJob] renamed to [job], and with the outputs of that job that it references
     * moved to the directory of [job].
     */
    private fun SessionReport.withRenamedJob(capturedJob: String, job: String): SessionReport {
        val capturedJobDirectory = capturedDirectory / capturedJob
        val jobDirectory = capturedDirectory / job
        return withOutputFiles { file -> file.relativeTo(capturedJobDirectory)?.let { jobDirectory / it } ?: file }.apply {
            jobReports.orEmpty().filter { it.displayName == capturedJob }.forEach { it.displayName = job }
        }
    }

    /**
     * Returns a copy of the report with each output file that names an object moved to the location that [move] returns for it.
     */
    private fun SessionReport.withOutputFiles(move: (GcsUri) -> GcsUri): SessionReport = deepCopy().apply {
        val jobs = jobReports.orEmpty()
        val outputFiles = jobs.flatMap { it.outputFiles.orEmpty() } +
            jobs.flatMap { job -> job.executionReports.orEmpty().flatMap { it.outputFiles.orEmpty() } }
        for (file in outputFiles.mapNotNull { it.gcsOutputFile }) {
            GcsUri.parseOrNull(file.path)?.let { file.path = move(it).toString() }
        }
    }

    /**
     * Returns a copy of the client model that shares no value with it.
     */
    private fun <T : GenericJson> T.deepCopy(): T {
        val json = GsonFactory.getDefaultInstance()
        return json.fromString(json.toString(this), javaClass)
    }

    /**
     * Returns the path below the session directory moved from the directory of [capturedJob] to the directory of [job].
     */
    private fun String.movedToJob(capturedJob: String, job: String): String =
        if (startsWith("$capturedJob/")) "$job/${removePrefix("$capturedJob/")}" else this

    /**
     * The session, its outputs, and their creation times, as the files of a scenario directory hold them by their paths relative to
     * it, such as `session-full.json` and `outputs/mediumPhone/junit.xml`.
     */
    private class ScenarioFiles(
        files: Map<String, String>,
    ) {
        val session: Session = GsonFactory.getDefaultInstance().fromString(files.getValue(SESSION_FILE), Session::class.java)
        val outputs: Map<String, String>
        val outputCreationTimes: Map<String, Instant>

        init {
            val directory = sessionDirectory(session)
            val listing = files[LISTING_FILE]?.let { GsonFactory.getDefaultInstance().fromString(it, Objects::class.java) }
            val listedObjects = listing?.items.orEmpty().mapNotNull { item ->
                GcsUri(item.bucket, item.name).relativeTo(directory)?.let { relativePath -> relativePath to item }
            }
            val referencedPaths = session.referencedOutputFiles().mapNotNull { it.relativeTo(directory) }
            val capturedOutputs = files.filterKeys { it.startsWith(OUTPUTS_DIRECTORY) }
                .mapKeys { (path, _) -> path.removePrefix(OUTPUTS_DIRECTORY) }
            outputs = (referencedPaths + listedObjects.map { it.first }).associateWith { PLACEHOLDER } + capturedOutputs
            outputCreationTimes = listedObjects
                .mapNotNull { (relativePath, item) -> item.timeCreated?.let { relativePath to Instant.ofEpochMilli(it.value) } }
                .toMap()
        }
    }

    private companion object {
        private const val SESSION_FILE = "session-full.json"
        private const val LISTING_FILE = "objects.json"
        private const val OUTPUTS_DIRECTORY = "outputs/"
        private const val PLACEHOLDER = "Placeholder for a Device Run output that the fixtures leave out"
    }
}

/**
 * Returns the directory of the outputs of [session], `gs://<bucket>/automation/sessions/<session id>`.
 */
private fun sessionDirectory(session: Session): GcsUri =
    GcsUri.parse(session.sessionConfig.outputDirectoryConfig.gcsOutputDirectory.path) / session.name.substringAfterLast('/')

/**
 * Returns the path of this object below [directory], which [GcsUri.div] joins to it, or null when the object lies elsewhere.
 */
private fun GcsUri.relativeTo(directory: GcsUri): String? {
    val prefix = "${directory.objectName.removeSuffix("/")}/"
    return objectName.removePrefix(prefix).takeIf { bucket == directory.bucket && objectName.startsWith(prefix) }
}
