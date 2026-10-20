package io.technoirlab.device.run.core.storage

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.GenericUrl
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.errorResponse
import io.technoirlab.device.run.core.client.testStorageClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

class InputUploaderTest {
    private val transport = RecordingHttpTransport()
    private val storageManager = StorageManager(testStorageClient(transport))
    private val uploader = InputUploader(FileHashCache(), storageManager)

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `uploads a missing input under its content hash and file name`() {
        val apk = (tempDir / "app-debug.apk").apply { writeText("app") }
        transport.responses.add(errorResponse(404))
        transport.enqueueStorageUpload(MockLowLevelHttpResponse().setContent("""{"bucket": "results", "name": "$APP_OBJECT"}"""))

        val uploadedFile = uploader.upload("results", apk)

        assertThat(uploadedFile).isEqualTo(UploadedFile(GcsUri("results", APP_OBJECT), APP_SHA256))
        assertThat(transport.requests).extracting<String> { it.method }.containsExactly("GET", "POST", "PUT")
        assertThat(transport.requests.first().uri.rawPath).isEqualTo("/storage/v1/b/results/o/${APP_OBJECT.replace("/", "%2F")}")
        assertThat(GenericUrl(transport.requests[1].url).getFirst("name")).isEqualTo(APP_OBJECT)
        assertThat(transport.requests.last().body).isEqualTo("app")
    }

    @Test
    fun `shares identical inputs across directories and runners`() {
        val firstProjectApk = (tempDir / "first").createDirectories() / "app-debug.apk"
        val secondProjectApk = (tempDir / "second").createDirectories() / "app-debug.apk"
        firstProjectApk.writeText("app")
        secondProjectApk.writeText("app")
        transport.responses.add(errorResponse(404))
        transport.enqueueStorageUpload(MockLowLevelHttpResponse().setContent("""{"bucket": "results", "name": "$APP_OBJECT"}"""))
        transport.responses.add(
            MockLowLevelHttpResponse().setContent(
                """{"bucket": "results", "name": "$APP_OBJECT", "contentType": "application/octet-stream", "size": "3"}""",
            ),
        )
        val nextRunnerUploader = InputUploader(FileHashCache(), storageManager)

        val firstProjectFile = uploader.upload("results", firstProjectApk)
        val secondProjectFile = uploader.upload("results", secondProjectApk)
        val nextRunnerFile = nextRunnerUploader.upload("results", secondProjectApk)

        assertThat(listOf(secondProjectFile, nextRunnerFile)).containsOnly(firstProjectFile)
        assertThat(transport.requests).extracting<String> { it.method }.containsExactly("GET", "POST", "PUT", "GET")
        assertThat(transport.responses).isEmpty()
    }

    @Test
    fun `fails without uploading when the bucket denies access`() {
        val apk = (tempDir / "app-debug.apk").apply { writeText("app") }
        transport.responses.add(errorResponse(403))

        assertThatThrownBy { uploader.upload("results", apk) }
            .isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { assertThat(it.statusCode).isEqualTo(403) }
        assertThat(transport.requests).hasSize(1)
    }

    private companion object {
        private const val APP_SHA256 = "a172cedcae47474b615c54d510a5d84a8dea3032e958587430b413538be3f333"
        private const val APP_OBJECT = "automation/inputs/$APP_SHA256/app-debug.apk"
    }
}
