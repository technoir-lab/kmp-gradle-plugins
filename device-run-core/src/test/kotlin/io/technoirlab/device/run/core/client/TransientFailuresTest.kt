package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.auth.oauth2.UserCredentials
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.net.ConnectException

class TransientFailuresTest {
    private val transport = RecordingHttpTransport()

    @Test
    fun `treats a failure without a response as transient`() {
        val failure = ConnectException("Connection refused")

        val transient = failure.isTransient()

        assertThat(transient).isTrue()
    }

    @ParameterizedTest
    @ValueSource(ints = [408, 429, 500, 502, 503, 504])
    fun `treats a response that reports a temporary condition as transient`(statusCode: Int) {
        val failure = HttpResponseException.Builder(statusCode, null, HttpHeaders()).build()

        val transient = failure.isTransient()

        assertThat(transient).isTrue()
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 409])
    fun `treats a rejected request as permanent`(statusCode: Int) {
        val failure = HttpResponseException.Builder(statusCode, null, HttpHeaders()).build()

        val transient = failure.isTransient()

        assertThat(transient).isFalse()
    }

    @Test
    fun `treats a revoked refresh token as permanent`() {
        val credentials = UserCredentials.newBuilder()
            .setClientId("test-client")
            .setClientSecret("test-secret")
            .setRefreshToken("test-refresh-token")
            .setHttpTransportFactory { transport }
            .build()
        transport.responses.add(
            MockLowLevelHttpResponse()
                .setStatusCode(400)
                .setContent("""{"error": "invalid_grant", "error_description": "Token has been expired or revoked."}"""),
        )

        val failure = runCatching { credentials.refresh() }.exceptionOrNull()

        assertThat(failure).isInstanceOfSatisfying(IOException::class.java) { assertThat(it.isTransient()).isFalse() }
    }
}
