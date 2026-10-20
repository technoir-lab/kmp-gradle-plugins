package io.technoirlab.device.run.core.storage

import com.google.api.client.http.HttpResponseException
import com.google.api.client.http.HttpStatusCodes
import com.google.api.client.http.InputStreamContent
import com.google.api.services.storage.Storage
import com.google.api.services.storage.model.StorageObject
import io.technoirlab.device.run.core.client.collectPages
import io.technoirlab.device.run.core.client.executeOrNull
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Accesses the Cloud Storage objects of test inputs and session outputs.
 */
internal class StorageManager(
    private val storageClient: Storage,
) {
    /**
     * Returns the metadata of the object at [uri], or null when it does not exist.
     *
     * Other failures, such as missing permissions, are thrown instead of being reported as a missing object.
     */
    fun findObject(uri: GcsUri): StorageObject? = storageClient.objects().get(uri.bucket, uri.objectName).executeOrNull()

    /**
     * Lists the names of the objects whose names start with [prefix], following every result page.
     */
    fun listObjectNames(bucket: String, prefix: String): List<String> {
        val request = storageClient.objects().list(bucket).setPrefix(prefix).setFields(OBJECT_NAME_FIELDS)
        return collectPages(
            requestPage = { pageToken -> request.setPageToken(pageToken).execute() },
            items = { response -> response.items?.map { it.name } },
            nextPageToken = { it.nextPageToken },
        )
    }

    /**
     * Downloads the object at [uri] to [destination], creating its parent directories.
     *
     * @return false when the object does not exist; other failures are thrown and leave no partial file.
     */
    fun downloadObject(uri: GcsUri, destination: Path): Boolean {
        destination.parent.createDirectories()
        try {
            destination.outputStream().use { output ->
                storageClient.objects().get(uri.bucket, uri.objectName).executeMediaAndDownloadTo(output)
            }
            return true
        } catch (e: IOException) {
            destination.deleteIfExists()
            if (e is HttpResponseException && e.statusCode == HttpStatusCodes.STATUS_CODE_NOT_FOUND) {
                return false
            }
            throw e
        }
    }

    /**
     * Uploads [file] to the object at [uri], replacing an existing object.
     */
    fun uploadObject(file: Path, uri: GcsUri) {
        file.inputStream().use { input ->
            val content = InputStreamContent("application/octet-stream", input).setLength(file.fileSize())
            storageClient.objects()
                .insert(uri.bucket, StorageObject(), content)
                .setName(uri.objectName)
                .execute()
        }
    }

    private companion object {
        // Callers read only the object names, so listings leave out the other metadata of each object.
        private const val OBJECT_NAME_FIELDS = "items(name),nextPageToken"
    }
}
