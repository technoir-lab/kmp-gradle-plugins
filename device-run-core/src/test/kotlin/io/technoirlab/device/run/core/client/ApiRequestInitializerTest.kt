package io.technoirlab.device.run.core.client

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.util.Sleeper
import com.google.api.services.devicerun.v1alpha.model.CatalogDevice
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.UserCredentials
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Date

class ApiRequestInitializerTest {
    private val transport = RecordingHttpTransport()
    private val sleeps = mutableListOf<Long>()
    private val location = DeviceRunLocation("execution-project")
    private val credentials = GoogleCredentials.create(AccessToken("test-token", Date(Long.MAX_VALUE)))

    @Test
    fun `retries transient failures with backoff until the request succeeds`() {
        transport.responses.add(errorResponse(503))
        transport.responses.add(errorResponse(429))
        transport.enqueueFixture(CATALOG_DEVICE)

        val device = getDevice()

        assertThat(device.name).endsWith("/devices/$DEVICE_ID")
        assertThat(transport.requests).hasSize(3).allSatisfy { request ->
            assertThat(request.getFirstHeaderValue("Authorization")).isEqualTo("Bearer test-token")
            assertThat(request.getFirstHeaderValue("X-Goog-User-Project")).isEqualTo("execution-project")
        }
        assertThat(sleeps).hasSize(2)
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 403, 404])
    fun `fails invalid and forbidden requests without retrying`(statusCode: Int) {
        transport.responses.add(errorResponse(statusCode))

        assertThatThrownBy { getDevice() }
            .isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { assertThat(it.statusCode).isEqualTo(statusCode) }
        assertThat(transport.requests).hasSize(1)
        assertThat(sleeps).isEmpty()
    }

    @Test
    fun `gives up after the retry limit`() {
        repeat(RequestRetryPolicy.STANDARD.maxRetries + 1) { transport.responses.add(errorResponse(503)) }

        assertThatThrownBy { getDevice() }
            .isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { assertThat(it.statusCode).isEqualTo(503) }
        assertThat(transport.requests).hasSize(RequestRetryPolicy.STANDARD.maxRetries + 1)
        assertThat(sleeps).hasSize(RequestRetryPolicy.STANDARD.maxRetries)
    }

    @Test
    fun `refreshes a rejected token and keeps retrying transient failures`() {
        val userCredentials = UserCredentials.newBuilder()
            .setClientId("test-client")
            .setClientSecret("test-secret")
            .setRefreshToken("test-refresh-token")
            .setHttpTransportFactory { transport }
            .build()
        transport.responses.add(tokenResponse("first-token"))
        transport.responses.add(errorResponse(401))
        transport.responses.add(tokenResponse("refreshed-token"))
        transport.responses.add(errorResponse(503))
        transport.enqueueFixture(CATALOG_DEVICE)

        val device = getDevice(credentials = userCredentials)

        val apiRequests = transport.requests.filter { it.uri.host == "devicerun.googleapis.com" }
        assertThat(device.name).endsWith("/devices/$DEVICE_ID")
        assertThat(apiRequests.map { it.getFirstHeaderValue("Authorization") })
            .containsExactly("Bearer first-token", "Bearer refreshed-token", "Bearer refreshed-token")
        assertThat(apiRequests).allSatisfy { assertThat(it.getFirstHeaderValue("X-Goog-User-Project")).isEqualTo("execution-project") }
        assertThat(sleeps).hasSize(1)
    }

    @Test
    fun `stops retrying when interrupted and keeps the interrupt flag`() {
        transport.responses.add(errorResponse(503))

        val failure = runCatching { getDevice(sleeper = { throw InterruptedException() }) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        assertThat(failure).isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { assertThat(it.statusCode).isEqualTo(503) }
        assertThat(interrupted).isTrue()
        assertThat(transport.requests).hasSize(1)
    }

    /**
     * Requests a catalog device through a Device Run client whose requests the initializer prepares.
     */
    private fun getDevice(credentials: GoogleCredentials = this.credentials, sleeper: Sleeper = Sleeper { sleeps += it }): CatalogDevice =
        testDeviceRunClient(transport, location, credentials = credentials, sleeper = sleeper)
            .projects()
            .locations()
            .devices()
            .get(location.deviceName(DEVICE_ID))
            .execute()

    private companion object {
        private const val CATALOG_DEVICE = "catalog/device-mediumphone-arm-35.json"
        private const val DEVICE_ID = "mediumphone-arm-35"
    }
}
