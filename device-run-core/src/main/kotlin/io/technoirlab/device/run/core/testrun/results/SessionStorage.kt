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
     * Downloads the outputs of session [sessionId] in [bucket] into [mirrorRoot], keeping the paths below the session directory.
     *
     * Device Run uploads the outputs seconds after the session ends, and final reports can omit job and execution reports, so the
     * session directory is listed until its outputs settle: until a listing contains the objects of [expectedFiles] below the
     * session directory and equals the listing taken [SETTLE_INTERVAL] earlier. After [SETTLE_TIMEOUT], the last listing is
     * mirrored, and a warning names the expected objects that it lacks. An interrupted wait throws [InterruptedException].
     *
     * Directory placeholders and object names that would leave [mirrorRoot] are skipped, and so are objects deleted before they
     * could be downloaded. Without [includeDeviceLogs], the device logs of the executions, `<job>/<execution>/logcat.txt`, are
     * skipped too.
     */
    fun mirror(
        bucket: String,
        sessionId: String,
        mirrorRoot: Path,
        expectedFiles: Collection<GcsUri>,
        includeDeviceLogs: Boolean,
    ): List<MirroredArtifact> {
        val prefix = "$SESSIONS_DIRECTORY/$sessionId/"
        val expectedPaths = expectedFiles
            .filter { it.bucket == bucket && it.objectName.startsWith(prefix) }
            .map { it.objectName.removePrefix(prefix) }
            .toSet()
        val root = mirrorRoot.toAbsolutePath().normalize()
        return settledOutputs(bucket, sessionId, expectedPaths)
            .filter { relativePath -> includeDeviceLogs || !DEVICE_LOG.matches(relativePath) }
            .mapNotNull { relativePath -> mirror(GcsUri(bucket, prefix + relativePath), relativePath, root) }
    }

    /**
     * Lists the outputs of session [sessionId] until they settle, and returns their paths below the session directory.
     */
    private fun settledOutputs(bucket: String, sessionId: String, expectedPaths: Set<String>): List<String> {
        val prefix = "$SESSIONS_DIRECTORY/$sessionId/"
        val wait = Stopwatch.createStarted(ticker)
        var previous: Set<String>? = null
        while (true) {
            val listing = storageManager.listObjectNames(bucket, prefix).map { it.removePrefix(prefix) }
            val missing = expectedPaths - listing.toSet()
            if (missing.isEmpty() && listing.toSet() == previous) {
                return listing
            }
            if (wait.elapsed() >= SETTLE_TIMEOUT) {
                reportUnsettled(sessionId, missing)
                return listing
            }
            previous = listing.toSet()
            sleeper.sleep(SETTLE_INTERVAL.toMillis())
        }
    }

    private fun reportUnsettled(sessionId: String, missing: Set<String>) {
        if (missing.isEmpty()) {
            logger.warn(
                "Device Run was still uploading the outputs of session {} after {}, so the results can lack some of them.",
                sessionId,
                SETTLE_TIMEOUT.toDisplayString(),
            )
        } else {
            logger.warn(
                "Device Run did not upload {} of session {} within {}, so the results lack them.",
                missing.sorted().joinToString(),
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

        // Files pulled from the device lie deeper, below the artifacts directory of the execution.
        private val DEVICE_LOG = Regex("[^/]+/[^/]+/logcat\\.txt")

        // Device Run finished uploading within 5 seconds after the end of each observed session.
        private val SETTLE_INTERVAL = Duration.ofSeconds(5)
        private val SETTLE_TIMEOUT = Duration.ofMinutes(1)
    }
}
