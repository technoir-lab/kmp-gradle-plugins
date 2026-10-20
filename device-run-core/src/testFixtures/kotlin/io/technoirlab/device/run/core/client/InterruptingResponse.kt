package io.technoirlab.device.run.core.client

import com.google.api.client.testing.http.MockLowLevelHttpResponse

/**
 * A queued response that arrives after the thread was interrupted, as when a build is cancelled while a request is in flight.
 */
internal class InterruptingResponse : MockLowLevelHttpResponse()
