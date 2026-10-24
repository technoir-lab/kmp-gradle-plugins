package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.storage.model.Objects
import io.technoirlab.device.run.core.VirtualClock
import io.technoirlab.device.run.core.client.FakeCloudTransport
import io.technoirlab.device.run.core.client.deviceRunFixtures
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import io.technoirlab.device.run.core.client.testStorageClient
import io.technoirlab.device.run.core.recordLogs
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.StorageManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.div

class SessionStorageTest {
    private val cloud = FakeCloudTransport()
    private val clock = VirtualClock()
    private val logs = recordLogs()
    private val sessionStorage = SessionStorage(StorageManager(testStorageClient(cloud)), clock.sleeper, clock.ticker)

    @TempDir
    private lateinit var mirrorRoot: Path

    @Test
    fun `mirrors every listed output with its job and execution nesting once a listing repeats the previous one`() {
        cloud.listingPageSize = 2
        upload("mediumPhone/junit.xml", "<testsuite/>")
        upload("mediumPhone/execution-000/test_result.xml", "<testsuites/>")
        upload("mediumPhone/execution-000/artifacts/", "")
        upload("mediumPhone/execution-000-rerun-000/logcat.txt", "logcat")
        upload("../../escaped.txt", "escaped")

        val artifacts = mirror(expectedPaths = listOf("mediumPhone/junit.xml"))

        assertThat(artifacts).extracting<String> { it.relativePath }.containsExactly(
            "mediumPhone/execution-000-rerun-000/logcat.txt",
            "mediumPhone/execution-000/test_result.xml",
            "mediumPhone/junit.xml",
        )
        assertThat(mirrorRoot / "mediumPhone/junit.xml").content().isEqualTo("<testsuite/>")
        assertThat(mirrorRoot / "mediumPhone/execution-000-rerun-000/logcat.txt").content().isEqualTo("logcat")
        assertThat(clock.sleeps).containsExactly(Duration.ofSeconds(5))
        assertThat(logs.entries).containsExactly(
            Level.WARN to
                "Skipping the Device Run artifact gs://results/automation/sessions/$SESSION_ID/../../escaped.txt because its name " +
                "does not map to a file below ${mirrorRoot.toAbsolutePath().normalize()}.",
        )
    }

    @Test
    fun `waits until the listing contains the expected outputs of the session and stops changing`() {
        upload("mediumPhone/junit.xml", "<testsuite/>", afterListings = 1)
        upload("mediumPhone/execution-000/junit.xml", "<testsuites/>", afterListings = 2)
        val expectedFiles = listOf(
            sessionFile("mediumPhone/junit.xml"),
            sessionFile("mediumPhone/execution-000/junit.xml"),
            GcsUri("results", "automation/sessions/other-session/mediumPhone/junit.xml"),
            GcsUri("other-bucket", "automation/sessions/$SESSION_ID/mediumPhone/junit.xml"),
        )

        val outputs = sessionStorage.listOutputs("results", SESSION_ID, expectedFiles, outputsExpected = false)

        assertThat(outputs.paths).containsExactly("mediumPhone/execution-000/junit.xml", "mediumPhone/junit.xml")
        assertThat(clock.sleeps).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5))
        assertThat(logs.entries).isEmpty()
    }

    @Test
    fun `mirrors the retries that Device Run uploads after the first listing of a final report without executions`() {
        val session = parseDeviceRunFixture<Session>("sessions/report-without-executions/session-full.json")
        val listing = parseDeviceRunFixture<Objects>("sessions/report-without-executions/objects.json")
        val outputs = deviceRunFixtures("sessions/report-without-executions/outputs")
        // As observed, the first listing came about 3 seconds after the session ended, before the retries were uploaded.
        val listingTimes = generateSequence(Instant.parse(session.sessionReport.endTime).plusSeconds(3)) { it + SETTLE_INTERVAL }
        for (item in listing.items) {
            val relativePath = item.name.removePrefix("$SESSION_DIRECTORY/")
            val afterListings = listingTimes.takeWhile { it.toEpochMilli() < item.timeCreated.value }.count()
            upload(relativePath, outputs[relativePath] ?: "Placeholder for $relativePath", afterListings)
        }

        val artifacts = mirror(expectedPaths = listOf("smallPhone/junit.xml"))

        assertThat(artifacts).extracting<String> { it.relativePath }
            .containsExactlyInAnyOrderElementsOf(listing.items.map { it.name.removePrefix("$SESSION_DIRECTORY/") })
        assertThat(mirrorRoot / "smallPhone/execution-000-rerun-001/junit.xml")
            .content()
            .isEqualTo(outputs["smallPhone/execution-000-rerun-001/junit.xml"])
        assertThat(clock.sleeps).containsExactly(SETTLE_INTERVAL, SETTLE_INTERVAL)
    }

    @Test
    fun `mirrors the last listing after a minute and names the expected outputs that Device Run did not upload`() {
        upload("mediumPhone/execution-000/junit.xml", "<testsuites/>")
        upload("mediumPhone/junit.xml", "<testsuite/>", afterListings = 100)

        val artifacts = mirror(expectedPaths = listOf("mediumPhone/junit.xml", "mediumPhone/execution-000/logcat.txt"))

        assertThat(artifacts).extracting<String> { it.relativePath }.containsExactly("mediumPhone/execution-000/junit.xml")
        assertThat(clock.sleeps).hasSize(12).containsOnly(SETTLE_INTERVAL)
        assertThat(logs.entries).containsExactly(
            Level.WARN to
                "Device Run did not upload mediumPhone/execution-000/logcat.txt, mediumPhone/junit.xml of session $SESSION_ID within " +
                "1m, so the results lack them.",
        )
    }

    @Test
    fun `waits for the first outputs of a session whose tests ran`() {
        upload("mediumPhone/junit.xml", "<testsuite/>", afterListings = 2)

        val outputs = sessionStorage.listOutputs("results", SESSION_ID, expectedFiles = emptyList(), outputsExpected = true)

        assertThat(outputs.paths).containsExactly("mediumPhone/junit.xml")
        assertThat(clock.sleeps).containsExactly(SETTLE_INTERVAL, SETTLE_INTERVAL, SETTLE_INTERVAL)
    }

    @Test
    fun `stops waiting for a session whose tests ran once its outputs stop changing, whichever they are`() {
        upload("mediumPhone/execution-000/junit.xml", "<testsuites/>")

        val outputs = sessionStorage.listOutputs("results", SESSION_ID, expectedFiles = emptyList(), outputsExpected = true)

        assertThat(outputs.paths).containsExactly("mediumPhone/execution-000/junit.xml")
        assertThat(clock.sleeps).containsExactly(SETTLE_INTERVAL)
        assertThat(logs.entries).isEmpty()
    }

    @Test
    fun `warns after a minute when a session whose tests ran has no outputs`() {
        val outputs = sessionStorage.listOutputs("results", SESSION_ID, expectedFiles = emptyList(), outputsExpected = true)

        assertThat(outputs.paths).isEmpty()
        assertThat(clock.sleeps).hasSize(12).containsOnly(SETTLE_INTERVAL)
        assertThat(logs.entries).containsExactly(Level.WARN to "Device Run uploaded no outputs of session $SESSION_ID within 1m.")
    }

    @Test
    fun `settles at once on no outputs of a session whose tests did not run`() {
        val outputs = sessionStorage.listOutputs("results", SESSION_ID, expectedFiles = emptyList(), outputsExpected = false)

        assertThat(outputs.paths).isEmpty()
        assertThat(clock.sleeps).containsExactly(SETTLE_INTERVAL)
        assertThat(logs.entries).isEmpty()
    }

    @Test
    fun `mirrors the last listing after a minute when Device Run keeps uploading outputs`() {
        for (listings in 0..12) {
            upload("mediumPhone/execution-000/artifacts/sdcard/output-$listings.txt", "output", afterListings = listings)
        }

        val artifacts = mirror(expectedPaths = emptyList())

        assertThat(artifacts).hasSize(13)
        assertThat(clock.sleeps).hasSize(12)
        assertThat(logs.entries).containsExactly(
            Level.WARN to
                "Device Run was still uploading the outputs of session $SESSION_ID after 1m, so the results can lack some of them.",
        )
    }

    @Test
    fun `skips outputs that are deleted before they can be downloaded`() {
        upload("mediumPhone/junit.xml", "<testsuite/>")
        upload("mediumPhone/execution-000/logcat.txt", "logcat")
        cloud.onRequest = { request ->
            if (request.method == "GET" && request.path.endsWith("junit.xml")) {
                cloud.delete(sessionFile("mediumPhone/junit.xml"))
            }
        }

        val artifacts = mirror(expectedPaths = emptyList())

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
        upload("mediumPhone/execution-000/logcat.txt", "logcat")
        upload("mediumPhone/execution-000/artifacts/sdcard/logcat.txt", "pulled")
        upload("mediumPhone/execution-000/test_result.xml", "<testsuite/>")

        val outputs = sessionStorage.listOutputs(
            bucket = "results",
            sessionId = SESSION_ID,
            expectedFiles = listOf(sessionFile("mediumPhone/execution-000/logcat.txt")),
            outputsExpected = true,
        )
        val artifacts = sessionStorage.mirror(outputs.withoutDeviceLogs, mirrorRoot)

        assertThat(artifacts).extracting<String> { it.relativePath }.containsExactly(
            "mediumPhone/execution-000/artifacts/sdcard/logcat.txt",
            "mediumPhone/execution-000/test_result.xml",
        )
        assertThat(outputs.deviceLogs.paths).containsExactly("mediumPhone/execution-000/logcat.txt")
        assertThat(mirrorRoot / "mediumPhone/execution-000/logcat.txt").doesNotExist()
        assertThat(cloud.requests.filter { it.path.endsWith("logcat.txt") }).hasSize(1)
    }

    @Test
    fun `stops waiting for the outputs when the thread is interrupted`() {
        upload("mediumPhone/junit.xml", "<testsuite/>")
        clock.interruptNextSleep()

        assertThatThrownBy { mirror(expectedPaths = emptyList()) }.isInstanceOf(InterruptedException::class.java)
        assertThat(mirrorRoot / "mediumPhone/junit.xml").doesNotExist()
    }

    private fun mirror(expectedPaths: List<String>): List<MirroredArtifact> {
        val outputs = sessionStorage.listOutputs("results", SESSION_ID, expectedPaths.map(::sessionFile), outputsExpected = true)
        return sessionStorage.mirror(outputs, mirrorRoot)
    }

    private fun upload(relativePath: String, content: String, afterListings: Int = 0) {
        cloud.upload(sessionFile(relativePath), content, afterListings)
    }

    private fun sessionFile(relativePath: String): GcsUri = GcsUri("results", "$SESSION_DIRECTORY/$relativePath")

    private companion object {
        private const val SESSION_ID = "session-b059e4c1-75cf-481d-b366-be7600c11d96"
        private const val SESSION_DIRECTORY = "automation/sessions/$SESSION_ID"
        private val SETTLE_INTERVAL = Duration.ofSeconds(5)
    }
}
