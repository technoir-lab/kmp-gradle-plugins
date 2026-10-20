package io.technoirlab.device.run.core.files

import java.nio.file.Path

/**
 * A file pushed to the device before the tests run, which is validated before it is prepared.
 *
 * Exactly one of [gcsUri][PushedFileOptions.gcsUri] and [localFile][PushedFileOptions.localFile] must be set.
 *
 * @property name the name of the file, which identifies it in messages.
 * @property gcsUri an existing Cloud Storage object, such as `gs://my-bucket/fixtures/data.json`.
 * @property localFile a local file, which is uploaded to the bucket.
 * @property destinationPath the required absolute path of the file on the device, such as `/sdcard/data.json`.
 */
data class PushedFileOptions(
    val name: String,
    val gcsUri: String? = null,
    val localFile: Path? = null,
    val destinationPath: String? = null,
)
