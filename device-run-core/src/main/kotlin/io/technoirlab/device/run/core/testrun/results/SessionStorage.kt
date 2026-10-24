package io.technoirlab.device.run.core.testrun.results

import com.google.api.client.util.Sleeper
import com.google.common.base.Ticker
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.StorageManager
import io.technoirlab.device.run.core.testrun.session.Deadline
import io.technoirlab.device.run.core.testrun.toDisplayString
import org.slf4j.LoggerFactory
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.div

/**
 * The Cloud Storage layout of Device Run sessions in a results bucket.
 *
 * Device Run writes the outputs of a session below `automation/sessions/<session id>/` and nests them by job and execution;
 * the runner keeps that nesting rather than requesting a flat layout.
 *
 * @param sleeper waits between the listings of session outputs.
 * @param ticker the monotonic time source of the wait for session outputs.
 */
internal class SessionStorage(
    private val storageManager: StorageManager,
    private val sleeper: Sleeper,
    private val ticker: Ticker,
) {
    private val logger = LoggerFactory.getLogger(SessionStorage::class.java)

    /**
     * Returns the output directory of sessions, `gs://<bucket>/automation/sessions`.
     */
    fun outputDirectory(bucket: String): GcsUri = GcsUri(bucket, SESSIONS_DIRECTORY)

    /**
     * Lists the outputs of session [sessionId] in [bucket] once Device Run has uploaded them.
     *
     * Device Run uploads the outputs seconds after the session ends, so the session directory is listed until it contains the
     * objects of [expectedFiles] below it and, when [executionOutputsExpected], outputs of an execution, which Device Run uploads
     * after the job report. A listing of exactly [expectedFiles] settles the outputs when they are complete, as the files that a
     * final report with every execution references are. Final reports can omit executions, so any other listing settles them
     * once it equals the listing taken [SETTLE_INTERVAL] earlier. After [SETTLE_TIMEOUT], the last listing counts, and a warning
     * names what it lacks and where Device Run may still upload it. An interrupted wait throws [InterruptedException].
     *
     * @param expectedFilesAreComplete whether [expectedFiles] name every output of the session.
     * @param executionOutputsExpected whether the executions of the session have outputs, as when its tests ran.
     */
    fun listOutputs(
        bucket: String,
        sessionId: String,
        expectedFiles: Collection<GcsUri>,
        expectedFilesAreComplete: Boolean,
        executionOutputsExpected: Boolean,
    ): SessionOutputs {
        val directory = outputDirectory(bucket) / sessionId
        val prefix = "${directory.objectName}/"
        val expectedPaths = expectedFiles.mapNotNull { it.relativeTo(directory) }.toSet()
        val deadline = Deadline(SETTLE_TIMEOUT, ticker)
        var previous: Set<String>? = null
        while (true) {
            val outputs = SessionOutputs(directory, storageManager.listObjectNames(bucket, prefix).map { it.removePrefix(prefix) })
            val listed = outputs.paths.toSet()
            val missing = expectedPaths - listed
            val lacksExecutionOutputs = executionOutputsExpected && !outputs.hasExecutionOutputs
            val complete = missing.isEmpty() && !lacksExecutionOutputs
            val settled = listed == previous || expectedFilesAreComplete && listed == expectedPaths
            if (complete && settled) {
                return outputs
            }
            if (deadline.hasPassed()) {
                reportUnsettled(sessionId, directory, missing, lacksExecutionOutputs)
                return outputs
            }
            previous = listed
            sleeper.sleep(SETTLE_INTERVAL.toMillis())
        }
    }

    /**
     * Downloads [outputs] into [mirrorRoot], keeping their paths below the session directory.
     *
     * Directory placeholders and object names that would leave [mirrorRoot] are skipped, and so are objects deleted before they
     * could be downloaded.
     */
    fun mirror(outputs: SessionOutputs, mirrorRoot: Path): List<MirroredArtifact> {
        val root = mirrorRoot.toAbsolutePath().normalize()
        return outputs.paths.mapNotNull { relativePath -> mirror(outputs.directory / relativePath, relativePath, root) }
    }

    private fun reportUnsettled(sessionId: String, directory: GcsUri, missing: Set<String>, lacksExecutionOutputs: Boolean) {
        val timeout = SETTLE_TIMEOUT.toDisplayString()
        val problem = when {
            missing.isNotEmpty() ->
                "did not upload ${missing.sorted().joinToString()} of session $sessionId within $timeout, so the results lack them"
            lacksExecutionOutputs ->
                "uploaded no outputs of the executions of session $sessionId within $timeout, so the results lack them"
            else -> "was still uploading the outputs of session $sessionId after $timeout, so the results can lack some of them"
        }
        logger.warn("Device Run {}; it may still upload them to {}.", problem, directory)
    }

    private fun mirror(uri: GcsUri, relativePath: String, root: Path): MirroredArtifact? {
        if (relativePath.isEmpty() || relativePath.endsWith('/')) {
            return null
        }
        val destination = try {
            (root / relativePath).normalize()
        } catch (_: InvalidPathException) {
            null
        }
        return when {
            destination == null || destination == root || !destination.startsWith(root) -> {
                logger.warn("Skipping the Device Run artifact {} because its name does not map to a file below {}.", uri, root)
                null
            }
            !storageManager.downloadObject(uri, destination) -> {
                logger.warn("Skipping the Device Run artifact {} because it was deleted before it could be downloaded.", uri)
                null
            }
            else -> MirroredArtifact(relativePath, destination)
        }
    }

    private companion object {
        private const val SESSIONS_DIRECTORY = "automation/sessions"

        // Device Run uploaded the outputs of each of 84 observed sessions in one burst, with at most 3 seconds between objects.
        // Final reports that listed every execution referenced exactly the outputs of each of 91 observed sessions.
        private val SETTLE_INTERVAL = Duration.ofSeconds(5)
        private val SETTLE_TIMEOUT = Duration.ofMinutes(1)
    }
}
