package io.technoirlab.device.run.core.files

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.reason
import io.technoirlab.device.run.core.storage.InputUploader
import io.technoirlab.device.run.core.storage.StorageManager
import kotlin.io.path.isRegularFile

/**
 * Resolves the files pushed to the device to Cloud Storage objects: existing objects are checked and local files are uploaded.
 */
internal class PushedFilePreparer(
    private val inputUploader: InputUploader,
    private val storageManager: StorageManager,
) {
    /**
     * Prepares [pushedFiles] in [bucket] and returns the objects that hold them.
     *
     * @throws DeviceRunException when a source does not exist or cannot be accessed.
     */
    fun prepare(bucket: String, pushedFiles: List<PushedFileSpec>): List<PreparedFile> = pushedFiles.map { prepare(bucket, it) }

    private fun prepare(bucket: String, file: PushedFileSpec): PreparedFile {
        val property = pushedFileOptionPath(file.name)
        return when (val source = file.source) {
            is PushSource.CloudFile -> {
                val storageObject = try {
                    storageManager.findObject(source.uri)
                } catch (e: GoogleJsonResponseException) {
                    throw DeviceRunException("$property.gcsUri ${source.uri} cannot be read: ${e.reason()}", e)
                } ?: throw DeviceRunException("$property.gcsUri ${source.uri} does not exist.")
                val checksum = storageObject.md5Hash?.let { "md5:$it" } ?: "crc32c:${storageObject.crc32c}"
                PreparedFile(file.name, source.uri, checksum, file.destinationPath)
            }
            is PushSource.LocalFile -> {
                if (!source.path.isRegularFile()) {
                    throw DeviceRunException("$property.localFile ${source.path} is not an existing file.")
                }
                val uploadedFile = try {
                    inputUploader.upload(bucket, source.path)
                } catch (e: GoogleJsonResponseException) {
                    throw DeviceRunException("Unable to upload $property.localFile to bucket $bucket: ${e.reason()}", e)
                }
                PreparedFile(file.name, uploadedFile.uri, "sha256:${uploadedFile.sha256}", file.destinationPath)
            }
        }
    }
}
