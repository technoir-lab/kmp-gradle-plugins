package io.technoirlab.device.run.core.storage

/**
 * A local file uploaded to Cloud Storage, with the SHA-256 hash of its content.
 */
internal data class UploadedFile(
    val uri: GcsUri,
    val sha256: String,
)
