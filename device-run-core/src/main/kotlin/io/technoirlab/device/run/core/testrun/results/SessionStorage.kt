package io.technoirlab.device.run.core.testrun.results

import com.google.api.client.util.Sleeper
import com.google.common.base.Stopwatch
import com.google.common.base.Ticker
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.StorageManager
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
     * Device Run uploads the outputs seconds after the session ends, and final reports can omit job and execution reports, so the
     * session directory is listed until its outputs settle: until a listing contains the objects of [expectedFiles] below the
     * session directory, has outputs when [outputsExpected], and equals the listing taken [SETTLE_INTERVAL] earlier. Device Run
     * uploads the job report first, so a listing with outputs contains it when Device Run wrote one. After [SETTLE_TIMEOUT], the
     * last listing counts, and a warning names what it lacks. An interrupted wait throws [InterruptedException].
     *
     * @param outputsExpected whether the session has outputs, such as a session whose tests ran.
     */
    fun listOutputs(bucket: String, sessionId: String, expectedFiles: Collection<GcsUri>, outputsExpected: Boolean): SessionOutputs {
        val directory = outputDirectory(bucket) / sessionId
        val prefix = "${directory.objectName}/"
        val expectedPaths = expectedFiles
            .filter { it.bucket == bucket && it.objectName.startsWith(prefix) }
            .map { it.objectName.removePrefix(prefix) }
            .toSet()
        val wait = Stopwatch.createStarted(ticker)
        var previous: Set<String>? = null
        while (true) {
            val listing = storageManager.listObjectNames(bucket, prefix).map { it.removePrefix(prefix) }
            val missing = expectedPaths - listing.toSet()
            val complete = missing.isEmpty() && (listing.isNotEmpty() || !outputsExpected)
            if (complete && listing.toSet() == previous) {
                return SessionOutputs(directory, listing)
            }
            if (wait.elapsed() >= SETTLE_TIMEOUT) {
                reportUnsettled(sessionId, missing, listing.isEmpty() && outputsExpected)
                return SessionOutputs(directory, listing)
            }
            previous = listing.toSet()
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

    private fun reportUnsettled(sessionId: String, missing: Set<String>, noOutputs: Boolean) {
        when {
            missing.isNotEmpty() -> logger.warn(
                "Device Run did not upload {} of session {} within {}, so the results lack them.",
                missing.sorted().joinToString(),
                sessionId,
                SETTLE_TIMEOUT.toDisplayString(),
            )
            noOutputs -> logger.warn("Device Run uploaded no outputs of session {} within {}.", sessionId, SETTLE_TIMEOUT.toDisplayString())
            else -> logger.warn(
                "Device Run was still uploading the outputs of session {} after {}, so the results can lack some of them.",
                sessionId,
                SETTLE_TIMEOUT.toDisplayString(),
            )
        }
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
        private val SETTLE_INTERVAL = Duration.ofSeconds(5)
        private val SETTLE_TIMEOUT = Duration.ofMinutes(1)
    }
}
