package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.auth.oauth2.ExternalAccountCredentials.SubjectTokenTypes
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.IdentityPoolCredentials
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

    @Test
    fun `treats a token exchange that reports a temporary condition as transient`() {
        val credentials = workloadIdentityCredentials()
        transport.responses.add(
            MockLowLevelHttpResponse()
                .setStatusCode(503)
                .setContent("""{"error": {"code": 503, "message": "The service is currently unavailable.", "status": "UNAVAILABLE"}}"""),
        )

        val failure = runCatching { credentials.refresh() }.exceptionOrNull()

        assertThat(failure).isInstanceOfSatisfying(IOException::class.java) { assertThat(it.isTransient()).isTrue() }
    }

    @Test
    fun `treats a rejected token exchange of credentials that impersonate a service account as permanent`() {
        val credentials = workloadIdentityCredentials(
            serviceAccountImpersonationUrl = "https://iamcredentials.googleapis.com/v1/projects/-/serviceAccounts/" +
                "test@test-project.iam.gserviceaccount.com:generateAccessToken",
        )
        transport.responses.add(
            MockLowLevelHttpResponse()
                .setStatusCode(400)
                .setContent("""{"error": "invalid_grant", "error_description": "The subject token has expired."}"""),
        )

        val failure = runCatching { credentials.refresh() }.exceptionOrNull()

        assertThat(failure).isInstanceOfSatisfying(IOException::class.java) { assertThat(it.isTransient()).isFalse() }
    }

    private fun workloadIdentityCredentials(serviceAccountImpersonationUrl: String? = null): GoogleCredentials =
        IdentityPoolCredentials.newBuilder()
            .setSubjectTokenSupplier { "test-subject-token" }
            .setAudience("//iam.googleapis.com/projects/1/locations/global/workloadIdentityPools/test-pool/providers/test-provider")
            .setSubjectTokenType(SubjectTokenTypes.JWT)
            .setServiceAccountImpersonationUrl(serviceAccountImpersonationUrl)
            .setHttpTransportFactory { transport }
            .build()
}
