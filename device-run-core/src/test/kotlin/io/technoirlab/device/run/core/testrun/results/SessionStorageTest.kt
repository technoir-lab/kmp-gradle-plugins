package io.technoirlab.device.run.core.testrun.results

import com.google.api.client.http.GenericUrl
import com.google.api.services.storage.model.Objects
import com.google.api.services.storage.model.StorageObject
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.enqueueJson
import io.technoirlab.device.run.core.client.errorResponse
import io.technoirlab.device.run.core.client.testStorageClient
import io.technoirlab.device.run.core.recordLogs
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.StorageManager
import io.technoirlab.device.run.core.storage.enqueueStorageDownload
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.io.path.div

class SessionStorageTest {
    private val transport = RecordingHttpTransport()
    private val logs = recordLogs()
    private val sessionStorage = SessionStorage(
        StorageManager(testStorageClient(transport)),
    )

    @TempDir
    private lateinit var mirrorRoot: Path

    @Test
    fun `mirrors every listed artifact with its job and execution nesting`() {
        enqueueListing(
            listOf("mediumPhone/junit.xml", "mediumPhone/execution-000/test_result.xml", "mediumPhone/execution-000/artifacts/"),
            "page-2",
        )
        enqueueListing(listOf("mediumPhone/execution-000-rerun-000/logcat.txt", "../../escaped.txt"))
        transport.enqueueStorageDownload("<testsuite/>")
        transport.enqueueStorageDownload("<testsuites/>")
        transport.enqueueStorageDownload("logcat")

        val artifacts = sessionStorage.mirror(
            bucket = "results",
            sessionId = SESSION_ID,
            mirrorRoot = mirrorRoot,
            referencedFiles = emptyList(),
            includeDeviceLogs = true,
        )

        assertThat(artifacts).extracting<String> { it.relativePath }.containsExactly(
            "mediumPhone/junit.xml",
            "mediumPhone/execution-000/test_result.xml",
            "mediumPhone/execution-000-rerun-000/logcat.txt",
        )
        assertThat(mirrorRoot / "mediumPhone/junit.xml").content().isEqualTo("<testsuite/>")
        assertThat(mirrorRoot / "mediumPhone/execution-000-rerun-000/logcat.txt").content().isEqualTo("logcat")
        assertThat(transport.requests.take(2).map { GenericUrl(it.url) }).allSatisfy { url ->
            assertThat(url.getFirst("prefix")).isEqualTo("automation/sessions/$SESSION_ID/")
            assertThat(url.getFirst("fields")).isEqualTo("items(name),nextPageToken")
        }
        assertThat(GenericUrl(transport.requests[1].url).getFirst("pageToken")).isEqualTo("page-2")
        assertThat(logs.entries).containsExactly(
            Level.WARN to
                "Skipping the Device Run artifact gs://results/automation/sessions/$SESSION_ID/../../escaped.txt because its name " +
                "does not map to a file below ${mirrorRoot.toAbsolutePath().normalize()}.",
        )
    }

    @Test
    fun `adds referenced artifacts that the listing misses and skips deleted ones`() {
        enqueueListing(listOf("mediumPhone/junit.xml"))
        transport.responses.add(errorResponse(404))
        transport.enqueueStorageDownload("logcat")
        val referencedFiles = listOf(
            GcsUri("results", "automation/sessions/$SESSION_ID/mediumPhone/execution-000/logcat.txt"),
            GcsUri("results", "automation/sessions/other-session/mediumPhone/junit.xml"),
        )

        val artifacts = sessionStorage.mirror(
            bucket = "results",
            sessionId = SESSION_ID,
            mirrorRoot = mirrorRoot,
            referencedFiles = referencedFiles,
            includeDeviceLogs = true,
        )

        assertThat(artifacts).extracting<String> { it.relativePath }.containsExactly("mediumPhone/execution-000/logcat.txt")
        assertThat(mirrorRoot / "mediumPhone/junit.xml").doesNotExist()
        assertThat(logs.entries).containsExactly(
            Level.WARN to
                "Skipping the Device Run artifact gs://results/automation/sessions/$SESSION_ID/mediumPhone/junit.xml because it was " +
                "deleted before it could be downloaded.",
        )
    }

    @Test
    fun `skips the device logs of executions but not pulled files of the same name`() {
        enqueueListing(
            listOf(
                "mediumPhone/execution-000/logcat.txt",
                "mediumPhone/execution-000/artifacts/sdcard/logcat.txt",
                "mediumPhone/execution-000/test_result.xml",
            ),
        )
        transport.enqueueStorageDownload("pulled")
        transport.enqueueStorageDownload("<testsuite/>")
        val referencedFiles = listOf(GcsUri("results", "automation/sessions/$SESSION_ID/mediumPhone/execution-001/logcat.txt"))

        val artifacts = sessionStorage.mirror(
            bucket = "results",
            sessionId = SESSION_ID,
            mirrorRoot = mirrorRoot,
            referencedFiles = referencedFiles,
            includeDeviceLogs = false,
        )

        assertThat(artifacts).extracting<String> { it.relativePath }.containsExactly(
            "mediumPhone/execution-000/artifacts/sdcard/logcat.txt",
            "mediumPhone/execution-000/test_result.xml",
        )
        assertThat(mirrorRoot / "mediumPhone/execution-000/logcat.txt").doesNotExist()
        assertThat(transport.responses).isEmpty()
    }

    private fun enqueueListing(relativePaths: List<String>, nextPageToken: String? = null) {
        val objects = relativePaths.map { StorageObject().setBucket("results").setName("automation/sessions/$SESSION_ID/$it") }
        transport.enqueueJson(Objects().setItems(objects).setNextPageToken(nextPageToken))
    }

    private companion object {
        private const val SESSION_ID = "session-7f0ebc4c-61d5-4677-b6c2-be6033b75a86"
    }
}
