package io.technoirlab.device.run.core.client

import com.google.api.client.json.gson.GsonFactory
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText

class CredentialsLoaderTest {
    private val environment = mutableMapOf<String, String>()
    private val loader = CredentialsLoader(environment::get)

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `each load refreshes tokens with the current credentials file and transport`() {
        val firstTransport = RecordingHttpTransport().apply { enqueueToken("first-token") }
        val secondTransport = RecordingHttpTransport().apply { enqueueToken("second-token") }

        environment[CREDENTIALS_ENV_VAR] = userCredentialsFile("first.json", "first-refresh-token").toString()
        val first = loader.load { firstTransport }
        environment[CREDENTIALS_ENV_VAR] = userCredentialsFile("second.json", "second-refresh-token").toString()
        val second = loader.load { secondTransport }
        first.refresh()
        second.refresh()

        assertThat(first.accessToken?.tokenValue).isEqualTo("first-token")
        assertThat(second.accessToken?.tokenValue).isEqualTo("second-token")
        assertThat(firstTransport.requests).singleElement().satisfies({ request ->
            assertThat(request.url).isEqualTo("https://oauth2.googleapis.com/token")
            assertThat(request.body).contains("refresh_token=first-refresh-token")
        })
        assertThat(secondTransport.requests).singleElement().satisfies({ request ->
            assertThat(request.url).isEqualTo("https://oauth2.googleapis.com/token")
            assertThat(request.body).contains("refresh_token=second-refresh-token")
        })
    }

    @Test
    fun `applies the quota project from the environment`() {
        environment[CREDENTIALS_ENV_VAR] = userCredentialsFile("credentials.json", "refresh-token").toString()
        environment["GOOGLE_CLOUD_QUOTA_PROJECT"] = "environment-project"

        val credentials = loader.load { RecordingHttpTransport() }

        assertThat(credentials.quotaProjectId).isEqualTo("environment-project")
    }

    @Test
    fun `rejects a credentials path that is not a file`() {
        environment[CREDENTIALS_ENV_VAR] = tempDir.toString()

        assertThatThrownBy { loader.load { RecordingHttpTransport() } }
            .isInstanceOf(IOException::class.java)
            .hasMessage("$CREDENTIALS_ENV_VAR is set to '$tempDir', which is not a file.")
    }

    private fun userCredentialsFile(name: String, refreshToken: String): Path = (tempDir / name).apply {
        val credentials = mapOf(
            "type" to "authorized_user",
            "client_id" to "test-client",
            "client_secret" to "test-secret",
            "refresh_token" to refreshToken,
            "quota_project_id" to "file-project",
        )
        writeText(GsonFactory.getDefaultInstance().toString(credentials))
    }

    private fun RecordingHttpTransport.enqueueToken(accessToken: String) {
        responses.add(tokenResponse(accessToken))
    }

    private companion object {
        private const val CREDENTIALS_ENV_VAR = "GOOGLE_APPLICATION_CREDENTIALS"
    }
}
