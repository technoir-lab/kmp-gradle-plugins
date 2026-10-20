package io.technoirlab.device.run.core.files

import io.technoirlab.device.run.core.storage.GcsUri
import java.nio.file.Path

/**
 * Validated files pushed to the device.
 *
 * @property bucket the bucket that receives uploaded local files.
 */
internal data class PushedFilesSpec(
    val bucket: String,
    val files: List<PushedFileSpec>,
)

/**
 * A file pushed to the device before the tests run.
 */
internal data class PushedFileSpec(
    val name: String,
    val source: PushSource,
    val destinationPath: String,
)

/**
 * The source of a pushed file.
 */
internal sealed interface PushSource {
    /**
     * An existing Cloud Storage object.
     */
    data class CloudFile(
        val uri: GcsUri,
    ) : PushSource

    /**
     * A local file, uploaded to the bucket before the session is created.
     */
    data class LocalFile(
        val path: Path,
    ) : PushSource
}
