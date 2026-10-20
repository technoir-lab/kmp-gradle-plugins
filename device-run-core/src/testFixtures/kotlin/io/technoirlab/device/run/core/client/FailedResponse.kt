package io.technoirlab.device.run.core.client

import com.google.api.client.testing.http.MockLowLevelHttpResponse
import java.io.IOException

/**
 * A queued response that fails its request with [exception] instead of returning, as when a connection is lost.
 */
internal class FailedResponse(
    val exception: IOException,
) : MockLowLevelHttpResponse()
