package io.technoirlab.device.run.core.files

import io.technoirlab.device.run.core.storage.GcsUri

/**
 * A file pushed to the device, resolved to the Cloud Storage object that holds it.
 *
 * @property name the name of the pushed file, as in its [PushedFileOptions].
 * @property uri the `gs://` URI of the object.
 * @property checksum a checksum of the object content, prefixed with its algorithm, such as `sha256:<hex digest>`.
 * @property destinationPath the absolute path of the file on the device.
 */
data class PreparedFile(
    val name: String,
    val uri: GcsUri,
    val checksum: String,
    val destinationPath: String,
)
