package io.technoirlab.device.run.core.storage

import com.google.api.client.testing.http.MockLowLevelHttpResponse
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import java.net.URI

internal fun RecordingHttpTransport.enqueueStorageUpload(response: MockLowLevelHttpResponse) {
    responses.add(MockLowLevelHttpResponse().addHeader("Location", STORAGE_UPLOAD_URI.toString()))
    responses.add(response)
}

internal fun RecordingHttpTransport.enqueueStorageDownload(content: String) {
    responses.add(
        MockLowLevelHttpResponse()
            .setContentType("application/octet-stream")
            .setContent(content)
            .setContentLength(content.toByteArray().size.toLong()),
    )
}

private val STORAGE_UPLOAD_URI = URI("https://storage.googleapis.com/upload/session")
