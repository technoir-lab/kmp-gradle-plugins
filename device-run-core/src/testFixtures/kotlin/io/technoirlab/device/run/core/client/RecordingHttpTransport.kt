package io.technoirlab.device.run.core.client

import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import java.net.URI

internal class RecordingHttpTransport : MockHttpTransport() {
    val responses = ArrayDeque<MockLowLevelHttpResponse>()
    val requests = mutableListOf<RecordedHttpRequest>()
    var shutdownCount = 0
        private set

    override fun buildRequest(method: String, url: String): RecordedHttpRequest {
        val response = checkNotNull(responses.removeFirstOrNull()) { "No response queued for $method $url" }
        if (response.contentType == null) {
            response.setContentType("application/json")
        }
        return RecordedHttpRequest(method, URI(url))
            .apply { setResponse(response) }
            .also { requests.add(it) }
    }

    override fun shutdown() {
        shutdownCount++
    }
}
