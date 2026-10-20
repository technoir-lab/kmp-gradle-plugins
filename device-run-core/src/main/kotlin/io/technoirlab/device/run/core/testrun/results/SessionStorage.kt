package io.technoirlab.device.run.core.testrun.results

import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.StorageManager
import org.slf4j.LoggerFactory
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlin.io.path.div

/**
 * The Cloud Storage layout of Device Run sessions in a results bucket.
 *
 * Device Run writes the outputs of a session below `automation/sessions/<session id>/` and nests them by job and execution;
 * the runner keeps that nesting rather than requesting a flat layout.
 */
internal class SessionStorage(
    private val storageManager: StorageManager,
) {
    private val logger = LoggerFactory.getLogger(SessionStorage::class.java)

    /**
     * Returns the output directory of sessions, `gs://<bucket>/automation/sessions`.
     */
    fun outputDirectory(bucket: String): GcsUri = GcsUri(bucket, SESSIONS_DIRECTORY)

    /**
     * Downloads the artifacts of session [sessionId] in [bucket] into [mirrorRoot], keeping the paths below the session directory.
     *
     * Artifacts are discovered by listing the session directory, because final reports can omit job and execution reports;
     * [referencedFiles] from reports add objects that the listing misses. Directory placeholders and object names that would
     * leave [mirrorRoot] are skipped, and so are objects deleted before they could be downloaded. Without [includeDeviceLogs],
     * the device logs of the executions, `<job>/<execution>/logcat.txt`, are skipped too.
     */
    fun mirror(
        bucket: String,
        sessionId: String,
        mirrorRoot: Path,
        referencedFiles: Collection<GcsUri>,
        includeDeviceLogs: Boolean,
    ): List<MirroredArtifact> {
        val prefix = "$SESSIONS_DIRECTORY/$sessionId/"
        val listedFiles = storageManager.listObjectNames(bucket, prefix).map { GcsUri(bucket, it) }
        val sessionFiles = (listedFiles + referencedFiles.filter { it.bucket == bucket && it.objectName.startsWith(prefix) }).distinct()
        val root = mirrorRoot.toAbsolutePath().normalize()
        return sessionFiles.mapNotNull { uri ->
            val relativePath = uri.objectName.removePrefix(prefix)
            if (includeDeviceLogs || !DEVICE_LOG.matches(relativePath)) mirror(uri, relativePath, root) else null
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
    }
}
