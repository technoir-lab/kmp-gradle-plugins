package io.technoirlab.device.run.core.client

import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import java.net.URI

internal class RecordedHttpRequest(
    val method: String,
    val uri: URI,
) : MockLowLevelHttpRequest(uri.toString()) {
    var body = ""
        private set

    override fun execute(): LowLevelHttpResponse {
        // Capture streamed uploads before the client closes their source files.
        body = contentAsString
        (response as? FailedResponse)?.let { throw it.exception }
        if (response is InterruptingResponse) {
            Thread.currentThread().interrupt()
        }
        return super.execute()
    }
}
