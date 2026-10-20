package io.technoirlab.device.run.core.files

import com.google.api.client.testing.http.MockLowLevelHttpResponse
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.errorResponse
import io.technoirlab.device.run.core.client.testStorageClient
import io.technoirlab.device.run.core.storage.FileHashCache
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.InputUploader
import io.technoirlab.device.run.core.storage.StorageManager
import io.technoirlab.device.run.core.storage.enqueueStorageUpload
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.writeText

class PushedFilePreparerTest {
    private val transport = RecordingHttpTransport()
    private val storageManager = StorageManager(testStorageClient(transport))
    private val preparer = PushedFilePreparer(InputUploader(FileHashCache(), storageManager), storageManager)
    private val cloudFile = PushedFileSpec("fixture", PushSource.CloudFile(GcsUri("fixtures", "data/fixture.bin")), "/sdcard/fixture.bin")

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `records the object and content checksum of each pushed file`() {
        val localFile = (tempDir / "config.json").apply { writeText("""{"debug": true}""") }
        transport.responses.add(errorResponse(404))
        transport.enqueueStorageUpload(MockLowLevelHttpResponse().setContent("""{"bucket": "results", "name": "config.json"}"""))
        transport.responses.add(
            MockLowLevelHttpResponse().setContent(
                """{"bucket": "fixtures", "name": "data/fixture.bin", "md5Hash": "XUFAKrxLKna5cZ2REBfFkg=="}""",
            ),
        )

        val preparedFiles = preparer.prepare(
            "results",
            listOf(PushedFileSpec("config", PushSource.LocalFile(localFile), "/sdcard/config.json"), cloudFile),
        )

        assertThat(preparedFiles).containsExactly(
            PreparedFile(
                name = "config",
                uri = GcsUri("results", "automation/inputs/$CONFIG_SHA256/config.json"),
                checksum = "sha256:$CONFIG_SHA256",
                destinationPath = "/sdcard/config.json",
            ),
            PreparedFile("fixture", GcsUri("fixtures", "data/fixture.bin"), "md5:XUFAKrxLKna5cZ2REBfFkg==", "/sdcard/fixture.bin"),
        )
    }

    @ParameterizedTest
    @CsvSource(
        "404, deviceRun.files.push[\"fixture\"].gcsUri gs://fixtures/data/fixture.bin does not exist.",
        "403, deviceRun.files.push[\"fixture\"].gcsUri gs://fixtures/data/fixture.bin cannot be read: 403 Access denied",
    )
    fun `reports missing and unreadable cloud sources with their DSL property`(statusCode: Int, message: String) {
        transport.responses.add(errorResponse(statusCode, "Access denied"))

        assertThatThrownBy { preparer.prepare("results", listOf(cloudFile)) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(message)
    }

    private companion object {
        private const val CONFIG_SHA256 = "5ecd15ca290addbb878a1df42071196b54781a1c6f620010baeb948e518144c9"
    }
}
