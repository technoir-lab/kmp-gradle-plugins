package io.technoirlab.device.run.core.files

import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.GcsUriSerializer
import kotlinx.serialization.Serializable

/**
 * The JSON model of the manifest of the files pushed to the device.
 */
@Serializable
internal data class PushManifest(
    val files: List<Entry>,
) {
    /**
     * A pushed file.
     *
     * @property uri the Cloud Storage object that holds the file.
     * @property checksum a checksum of the object content, prefixed with its algorithm.
     */
    @Serializable
    data class Entry(
        val name: String,
        @Serializable(with = GcsUriSerializer::class)
        val uri: GcsUri,
        val checksum: String,
        val destinationPath: String,
    )
}
