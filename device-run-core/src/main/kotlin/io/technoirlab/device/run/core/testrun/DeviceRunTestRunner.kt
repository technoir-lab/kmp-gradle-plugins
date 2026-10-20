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
     * Session outputs are mirrored below `device-run/` in the results directory, apart from device logs that the logcat policy
     * discards, and `device-run-session.json` identifies the session for manual cleanup if the runner stops before the session
     * does.
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

        val includeDeviceLogs = keepsDeviceLogs(spec.job.capture.logcat, finishedSession)
        val artifacts = mirror(spec.bucket, finishedSession, request.resultsDirectory / MIRROR_DIRECTORY, includeDeviceLogs)
        val context = ResultContext(
            jobDirectory = session.sessionConfig.jobConfigs.single().displayName,
            resultsDirectory = request.resultsDirectory,
            deviceName = device.name,
            device = request.catalogDevice,
            reportProperties = request.reportProperties,
        )
        return resultConverter.convert(finishedSession, artifacts, context)
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
     * Returns whether the device logs of [session] are downloaded. Device Run cannot discard them on its own, so
     * [ArtifactCapture.ON_FAILURE] is applied to the session result.
     */
    private fun keepsDeviceLogs(logcat: ArtifactCapture, session: Session): Boolean = when (logcat) {
        ArtifactCapture.DISABLED -> false
        ArtifactCapture.ON_FAILURE -> session.sessionReport?.result?.resultType != PASSED
        ArtifactCapture.ALWAYS -> true
    }

    private fun mirror(bucket: String, session: Session, mirrorRoot: Path, includeDeviceLogs: Boolean): List<MirroredArtifact> = try {
        sessionStorage.mirror(
            bucket = bucket,
            sessionId = session.name.substringAfterLast('/'),
            mirrorRoot = mirrorRoot,
            referencedFiles = referencedFiles(session),
            includeDeviceLogs = includeDeviceLogs,
        )
    } catch (e: GoogleJsonResponseException) {
        throw DeviceRunException(
            "Unable to download the outputs of Device Run session ${session.name} from bucket $bucket: ${e.reason()}",
            e,
        )
    }

    /**
     * Returns the output files that the reports of [session] reference, which can include objects that a listing misses.
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
        private const val METADATA_FILE = "device-run-session.json"
        private const val MIRROR_DIRECTORY = "device-run"
    }
}
