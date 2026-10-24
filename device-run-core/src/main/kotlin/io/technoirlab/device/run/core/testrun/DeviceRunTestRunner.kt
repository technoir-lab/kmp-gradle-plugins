package io.technoirlab.device.run.core.testrun

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.services.devicerun.v1alpha.model.Session
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.reason
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.testrun.apk.TestPackagePreparer
import io.technoirlab.device.run.core.testrun.apk.TestPackages
import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.job.OrchestratorVersions
import io.technoirlab.device.run.core.testrun.results.DeviceRunResultConverter
import io.technoirlab.device.run.core.testrun.results.MirroredArtifact
import io.technoirlab.device.run.core.testrun.results.ResultContext
import io.technoirlab.device.run.core.testrun.results.SessionOutputs
import io.technoirlab.device.run.core.testrun.results.SessionStorage
import io.technoirlab.device.run.core.testrun.session.SessionLifecycle
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div

/**
 * Runs the tests of one device in a Device Run session and converts its outcome into test results.
 *
 * The session runs synchronously: [run] returns after it finishes and the results are written.
 */
internal class DeviceRunTestRunner(
    private val orchestratorVersions: OrchestratorVersions,
    private val packagePreparer: TestPackagePreparer,
    private val sessionStorage: SessionStorage,
    private val sessionLifecycle: SessionLifecycle,
) {
    private val sessionConfigFactory = SessionConfigFactory()
    private val resultConverter = DeviceRunResultConverter()

    /**
     * Runs the tests of [testRun], writes their results to the results directory of its request, and returns whether they passed.
     *
     * Session outputs are mirrored below `device-run/` in the results directory once Device Run has uploaded them, apart from
     * device logs that the logcat policy discards for the verdict on the tests, and `device-run-session.json` identifies the session
     * for manual cleanup if the runner stops before the session does.
     *
     * @throws DeviceRunException when the orchestrator version is not available, or the session cannot be prepared, run, or
     * downloaded.
     */
    fun run(testRun: TestRunSpec): Boolean {
        val request = testRun.request
        val spec = testRun.options
        val device = testRun.device
        spec.job.orchestratorVersion?.let(orchestratorVersions::requireAvailable)

        val inputs = SessionInputs(
            device = device,
            packages = preparePackages(spec.bucket, request),
            pushedFiles = request.pushedFiles,
            testRunnerClass = request.instrumentationRunner,
            runnerArguments = testRun.runnerArguments,
            testRunName = request.name,
            labels = request.labels,
            outputDirectory = sessionStorage.outputDirectory(spec.bucket),
        )
        val session = sessionConfigFactory.create(spec, inputs)
        request.resultsDirectory.createDirectories()
        val finishedSession =
            sessionLifecycle.run(session, inputs.outputDirectory, spec.waitTimeout, request.resultsDirectory / METADATA_FILE)

        val context = ResultContext(
            jobDirectory = session.sessionConfig.jobConfigs.single().displayName,
            resultsDirectory = request.resultsDirectory,
            deviceName = device.name,
            device = request.catalogDevice,
            reportProperties = request.reportProperties,
        )
        val outputs = listOutputs(spec.bucket, finishedSession)
        val mirrorRoot = request.resultsDirectory / MIRROR_DIRECTORY
        // The verdict does not depend on the device logs, which the logcat policy keeps or discards for it.
        val artifacts = mirror(spec.bucket, finishedSession, outputs.withoutDeviceLogs, mirrorRoot)
        val verdict = resultConverter.evaluate(finishedSession, artifacts, context)
        val deviceLogs = if (keepsDeviceLogs(spec.job.capture.logcat, verdict.passed)) {
            mirror(spec.bucket, finishedSession, outputs.deviceLogs, mirrorRoot)
        } else {
            emptyList()
        }
        resultConverter.write(finishedSession, (artifacts + deviceLogs).sortedBy { it.relativePath }, context, verdict)
        return verdict.passed
    }

    /**
     * Uploads the test APK and the APKs of the tested application, which library tests do not have.
     */
    private fun preparePackages(bucket: String, request: TestRunRequest): TestPackages = try {
        packagePreparer.prepare(bucket, request.testApk, request.testedApks)
    } catch (e: GoogleJsonResponseException) {
        throw DeviceRunException("Unable to upload the APKs of ${request.name} to bucket $bucket: ${e.reason()}", e)
    }

    /**
     * Returns whether the device logs of tests that [passed] or not are downloaded. Device Run cannot discard them on its own, so
     * [ArtifactCapture.ON_FAILURE] is applied to the verdict on the tests, which can differ from the session result.
     */
    private fun keepsDeviceLogs(logcat: ArtifactCapture, passed: Boolean): Boolean = when (logcat) {
        ArtifactCapture.DISABLED -> false
        ArtifactCapture.ON_FAILURE -> !passed
        ArtifactCapture.ALWAYS -> true
    }

    /**
     * Lists the outputs of [session] once Device Run has uploaded the files that its reports reference and, when its tests ran to
     * completion, its first outputs, which include the job report.
     */
    private fun listOutputs(bucket: String, session: Session): SessionOutputs {
        val resultType = session.sessionReport?.result?.resultType
        return accessOutputs(bucket, session) {
            sessionStorage.listOutputs(
                bucket = bucket,
                sessionId = session.name.substringAfterLast('/'),
                expectedFiles = referencedFiles(session),
                outputsExpected = resultType == PASSED || resultType == FAILED,
            )
        }
    }

    private fun mirror(bucket: String, session: Session, outputs: SessionOutputs, mirrorRoot: Path): List<MirroredArtifact> =
        accessOutputs(bucket, session) { sessionStorage.mirror(outputs, mirrorRoot) }

    private fun <T> accessOutputs(bucket: String, session: Session, access: () -> T): T = try {
        access()
    } catch (e: GoogleJsonResponseException) {
        throw DeviceRunException(
            "Unable to download the outputs of Device Run session ${session.name} from bucket $bucket: ${e.reason()}",
            e,
        )
    }

    /**
     * Returns the output files that the reports of [session] reference.
     */
    private fun referencedFiles(session: Session): List<GcsUri> {
        val jobReports = session.sessionReport?.jobReports.orEmpty()
        val outputFiles = jobReports.flatMap { job ->
            job.outputFiles.orEmpty() + job.executionReports.orEmpty().flatMap { it.outputFiles.orEmpty() }
        }
        return outputFiles.mapNotNull { file -> file.gcsOutputFile?.path?.let { GcsUri.parseOrNull(it) } }
    }

    private companion object {
        private const val PASSED = "PASSED"
        private const val FAILED = "FAILED"
        private const val METADATA_FILE = "device-run-session.json"
        private const val MIRROR_DIRECTORY = "device-run"
    }
}
