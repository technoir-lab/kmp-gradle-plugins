package io.technoirlab.device.run.core.storage

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.fileSize
import kotlin.io.path.name

/**
 * Uploads local inputs to content-addressed objects, `automation/inputs/<sha256>/<file name>`, of a results bucket.
 *
 * The object name depends only on the content and the file name, so identical files of different test runs share one object. An
 * existing object of the same size is reused, and each object is checked at most once per runner.
 */
internal class InputUploader(
    private val hashCache: FileHashCache,
    private val storageManager: StorageManager,
) {
    private val uploads = ConcurrentHashMap<GcsUri, Lazy<Unit>>()

    /**
     * Ensures that [bucket] holds the content of [file] and returns the object that holds it.
     *
     * Failures other than a missing object, such as missing permissions, are thrown.
     */
    fun upload(bucket: String, file: Path): UploadedFile {
        val sha256 = hashCache.retrieveOrGenerateHash(file)
        val uri = GcsUri(bucket, "$INPUTS_PREFIX$sha256/${file.name}")
        uploads.computeIfAbsent(uri) { lazy { ensureUploaded(file, uri) } }.value
        return UploadedFile(uri, sha256)
    }

    @Suppress("UsePropertyAccessSyntax")
    private fun ensureUploaded(file: Path, uri: GcsUri) {
        val existingObject = storageManager.findObject(uri)
        // StorageObject is a Map, so its size property would count the metadata fields.
        if (existingObject?.getSize()?.toLong() != file.fileSize()) {
            storageManager.uploadObject(file, uri)
        }
    }

    private companion object {
        private const val INPUTS_PREFIX = "automation/inputs/"
    }
}
