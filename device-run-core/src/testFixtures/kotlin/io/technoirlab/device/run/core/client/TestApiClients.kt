package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.client.util.Sleeper
import com.google.api.services.devicerun.v1alpha.DeviceRun
import com.google.api.services.storage.Storage
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import java.util.Date

/**
 * Returns a Device Run client for [location] that authenticates with [credentials], by default a fixed access token, and sends
 * requests through [transport].
 */
internal fun testDeviceRunClient(
    transport: HttpTransport,
    location: DeviceRunLocation,
    retryPolicy: RequestRetryPolicy = RequestRetryPolicy.STANDARD,
    credentials: GoogleCredentials = GoogleCredentials.create(AccessToken("test-token", Date(Long.MAX_VALUE))),
    sleeper: Sleeper = Sleeper.DEFAULT,
): DeviceRun = createDeviceRunClient(
    transport = transport,
    credentials = credentials,
    location = location,
    retryPolicy = retryPolicy,
    clientName = "Device Runner Test",
    sleeper = sleeper,
)

/**
 * Returns a Cloud Storage client that sends unauthenticated requests through [transport].
 */
internal fun testStorageClient(transport: HttpTransport): Storage =
    Storage.Builder(transport, GsonFactory.getDefaultInstance(), null).setApplicationName("test").build()

/**
 * Returns an API error response with [statusCode], [message], and the canonical error [status] when there is one.
 */
internal fun errorResponse(statusCode: Int, message: String = "Rejected", status: String? = null): MockLowLevelHttpResponse {
    val error = buildMap {
        put("code", statusCode)
        put("message", message)
        status?.let { put("status", it) }
    }
    val content = GsonFactory.getDefaultInstance().toString(mapOf("error" to error))
    return MockLowLevelHttpResponse().setStatusCode(statusCode).setContent(content)
}

/**
 * Returns an OAuth token response that grants [accessToken] for an hour.
 */
internal fun tokenResponse(accessToken: String): MockLowLevelHttpResponse =
    MockLowLevelHttpResponse().setContent("""{"access_token": "$accessToken", "expires_in": 3600}""")
