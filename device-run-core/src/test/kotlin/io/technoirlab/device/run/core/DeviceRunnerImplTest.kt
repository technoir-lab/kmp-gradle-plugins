package io.technoirlab.device.run.core

import com.google.api.client.http.HttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.storage.model.Objects
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.UserCredentials
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import io.technoirlab.device.run.core.client.CloudConnector
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.StubCloudConnector
import io.technoirlab.device.run.core.client.deviceRunFixtures
import io.technoirlab.device.run.core.client.enqueueFixture
import io.technoirlab.device.run.core.client.errorResponse
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import io.technoirlab.device.run.core.client.tokenResponse
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import io.technoirlab.device.run.core.files.PushedFileOptions
import io.technoirlab.device.run.core.storage.enqueueStorageDownload
import io.technoirlab.device.run.core.testrun.TestRunRequest
import io.technoirlab.device.run.core.testrun.appTestRunRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.Date
import java.util.logging.Level
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

class DeviceRunnerImplTest {
    private val cloudConnector = StubCloudConnector(RecordingHttpTransport())
    private val transport = cloudConnector.transport
    private val runner = DeviceRunnerImpl(cloudConnector, CLIENT_NAME)

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `sets the API client log level to warning`() {
        val level = runner.apiClientLogger.level

        assertThat(level).isEqualTo(Level.WARNING)
    }

    @Test
    fun `validates device options before accessing Google Cloud`() {
        val offlineRunner = DeviceRunnerImpl(OfflineCloudConnector(), CLIENT_NAME)

        assertThatThrownBy { offlineRunner.resolveDevice(DEVICE.copy(deviceId = null)) }
            .isInstanceOf(InvalidOptionsException::class.java)
    }

    @Test
    fun `prepares no pushed files without accessing Google Cloud`() {
        val offlineRunner = DeviceRunnerImpl(OfflineCloudConnector(), CLIENT_NAME)

        val preparedFiles = offlineRunner.preparePushedFiles("example-bucket", files = emptyList())

        assertThat(preparedFiles).isEmpty()
    }

    @Test
    fun `validates pushed files before accessing Google Cloud`() {
        val offlineRunner = DeviceRunnerImpl(OfflineCloudConnector(), CLIENT_NAME)
        val files = listOf(PushedFileOptions(name = "input", gcsUri = "gs://example-bucket/input.txt"))

        assertThatThrownBy { offlineRunner.preparePushedFiles(bucket = null, files) }
            .isInstanceOf(InvalidOptionsException::class.java)
    }

    @Test
    fun `validates runner arguments before accessing Google Cloud`() {
        val offlineRunner = DeviceRunnerImpl(OfflineCloudConnector(), CLIENT_NAME)
        val request = request(tempDir / "app-free-debug-androidTest.apk", tempDir / "app-free-debug.apk", tempDir / RESULTS_PATH)
            .copy(instrumentationRunnerArguments = mapOf("testFile" to "/sdcard/tests.txt"))

        assertThatThrownBy { offlineRunner.runTests(request) }
            .isInstanceOf(InvalidOptionsException::class.java)
    }

    @Test
    fun `loads credentials once for all operations of a runner`() {
        val countingConnector = CountingCloudConnector(cloudConnector)
        val runner = DeviceRunnerImpl(countingConnector, CLIENT_NAME)
        repeat(2) { transport.enqueueFixture(CATALOG_DEVICE) }

        repeat(2) { runner.resolveDevice(DEVICE) }

        assertThat(countingConnector.credentialLoads).isEqualTo(1)
        assertThat(countingConnector.transportCreations).isEqualTo(1)
    }

    @Test
    fun `requests access tokens through the transport of the API clients`() {
        val runner = DeviceRunnerImpl(
            object : CloudConnector {
                override fun createCredentials(transportFactory: HttpTransportFactory): GoogleCredentials = UserCredentials.newBuilder()
                    .setClientId("test-client")
                    .setClientSecret("test-secret")
                    .setRefreshToken("test-refresh-token")
                    .setQuotaProjectId("quota-project")
                    .setHttpTransportFactory(transportFactory)
                    .build()

                override fun createHttpTransport(): HttpTransport = transport
            },
            CLIENT_NAME,
        )
        transport.responses.add(tokenResponse("access-token"))
        transport.enqueueFixture(CATALOG_DEVICE)

        runner.resolveDevice(DEVICE)

        assertThat(transport.requests.map { it.url }).containsExactly(
            "https://oauth2.googleapis.com/token",
            "https://devicerun.googleapis.com/v1alpha/projects/quota-project/locations/global/devices/mediumphone-arm-35",
        )
        assertThat(transport.requests.last().getFirstHeaderValue("Authorization")).isEqualTo("Bearer access-token")
    }

    @Test
    fun `shuts down the transport of a connection without a project before sending a request and connects again`() {
        val transports = mutableListOf<RecordingHttpTransport>()
        val projectlessRunner = DeviceRunnerImpl(
            object : CloudConnector {
                override fun createCredentials(transportFactory: HttpTransportFactory): GoogleCredentials =
                    GoogleCredentials.create(AccessToken("test-access-token", Date(Long.MAX_VALUE)))

                override fun createHttpTransport(): HttpTransport = RecordingHttpTransport().also { transports.add(it) }
            },
            CLIENT_NAME,
        )

        val firstFailure = catchThrowable { projectlessRunner.resolveDevice(DEVICE) }
        val secondFailure = catchThrowable { projectlessRunner.resolveDevice(DEVICE) }
        projectlessRunner.close()

        assertThat(firstFailure).isInstanceOf(DeviceRunException::class.java)
        assertThat(secondFailure).isInstanceOf(DeviceRunException::class.java)
        assertThat(transports).hasSize(2).allSatisfy { transport ->
            assertThat(transport.requests).isEmpty()
            assertThat(transport.shutdownCount).isEqualTo(1)
        }
    }

    @Test
    fun `closing shuts down only the transport of a runner that connected`() {
        val unusedRunner = DeviceRunnerImpl(OfflineCloudConnector(), CLIENT_NAME)
        transport.enqueueFixture(CATALOG_DEVICE)
        runner.resolveDevice(DEVICE)

        unusedRunner.close()
        runner.close()

        assertThat(transport.shutdownCount).isEqualTo(1)
    }

    @Test
    fun `identifies the client in the user agent of requests`() {
        transport.enqueueFixture(CATALOG_DEVICE)

        runner.resolveDevice(DEVICE)

        val userAgent = transport.requests.single().getFirstHeaderValue("User-Agent")
        assertThat(userAgent).startsWith("$CLIENT_NAME ")
    }

    @Test
    fun `runs tests through Device Run and Cloud Storage requests authenticated for the project of the credentials`() {
        val testApk = (tempDir / "app-free-debug-androidTest.apk").apply { writeText("test") }
        val testedApk = (tempDir / "app-free-debug.apk").apply { writeText("tested") }
        val resultsDirectory = tempDir / RESULTS_PATH
        transport.enqueueFlakyRetryRun()

        val passed = runner.runTests(request(testApk, testedApk, resultsDirectory))

        val suiteResult = (resultsDirectory / "test-result.pb").inputStream().use(TestSuiteResult::parseFrom)
        assertThat(passed).isTrue()
        assertThat(suiteResult.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(transport.requests.map { it.uri.host }.distinct()).containsExactlyInAnyOrder(
            "devicerun.googleapis.com",
            "storage.googleapis.com",
        )
        assertThat(transport.requests).allSatisfy { request ->
            assertThat(request.getFirstHeaderValue("Authorization")).isEqualTo("Bearer test-access-token")
            assertThat(request.getFirstHeaderValue("X-Goog-User-Project")).isEqualTo(StubCloudConnector.QUOTA_PROJECT_ID)
        }
        assertThat(transport.requests.filter { it.method == "POST" }.map { it.uri.rawPath })
            .containsExactly("/v1alpha/projects/${StubCloudConnector.QUOTA_PROJECT_ID}/locations/global/sessions")
        assertThat(transport.responses).isEmpty()
    }

    @Test
    fun `reports a request that fails on an interrupted thread as an interruption`() {
        val testApk = (tempDir / "app-free-debug-androidTest.apk").apply { writeText("test") }
        val testedApk = (tempDir / "app-free-debug.apk").apply { writeText("tested") }
        transport.responses.add(errorResponse(503))
        Thread.currentThread().interrupt()

        val failure = catchThrowable { runner.runTests(request(testApk, testedApk, tempDir / RESULTS_PATH)) }
        val interrupted = Thread.interrupted()

        assertThat(failure).isInstanceOf(InterruptedException::class.java).hasCauseInstanceOf(DeviceRunException::class.java)
        assertThat(interrupted).isFalse()
        assertThat(transport.requests).hasSize(1)
    }

    private fun request(testApk: Path, testedApk: Path, resultsDirectory: Path): TestRunRequest =
        appTestRunRequest(testApk, listOf(testedApk), resultsDirectory).copy(device = DEVICE.copy(locale = "fr-CA"))

    private fun RecordingHttpTransport.enqueueExistingObject(content: String) {
        responses.add(MockLowLevelHttpResponse().setContent("""{"bucket": "example-bucket", "size": "${content.length}"}"""))
    }

    /**
     * Queues the responses of a test run that finishes as the captured flaky retry session, whose device logs the default logcat
     * policy leaves out.
     */
    private fun RecordingHttpTransport.enqueueFlakyRetryRun() {
        enqueueExistingObject("test")
        enqueueExistingObject("tested")
        enqueueFixture("sessions/flaky-retry-passed/operation-created.json")
        // The finished session answers both the poll that sees it done and the request for its full view.
        repeat(2) { enqueueFixture("sessions/flaky-retry-passed/session-full.json") }
        enqueueFixture("sessions/flaky-retry-passed/objects.json")
        val listing = parseDeviceRunFixture<Objects>("sessions/flaky-retry-passed/objects.json")
        val outputs = deviceRunFixtures("sessions/flaky-retry-passed/outputs")
        for (item in listing.items) {
            val relativePath = item.name.split('/', limit = 4).last()
            if (!relativePath.matches(DEVICE_LOG)) {
                enqueueStorageDownload(outputs[relativePath] ?: "Content of $relativePath")
            }
        }
    }

    /**
     * Counts the credentials and transports that [delegate] creates.
     */
    private class CountingCloudConnector(
        private val delegate: CloudConnector,
    ) : CloudConnector {
        var credentialLoads = 0
            private set
        var transportCreations = 0
            private set

        override fun createCredentials(transportFactory: HttpTransportFactory): GoogleCredentials =
            delegate.createCredentials(transportFactory).also { credentialLoads++ }

        override fun createHttpTransport(): HttpTransport = delegate.createHttpTransport().also { transportCreations++ }
    }

    /**
     * Fails every attempt to access Google Cloud.
     */
    private class OfflineCloudConnector : CloudConnector {
        override fun createCredentials(transportFactory: HttpTransportFactory): GoogleCredentials =
            error("Google Cloud must not be accessed")

        override fun createHttpTransport(): HttpTransport = error("Google Cloud must not be accessed")
    }

    private companion object {
        private const val CLIENT_NAME = "Device Runner Test"
        private const val CATALOG_DEVICE = "catalog/device-mediumphone-arm-35.json"
        private const val RESULTS_PATH = "outputs/androidTest-results/managedDevice/freeDebug/mediumPhone"
        private val DEVICE = DeviceOptions(name = "mediumPhone", deviceId = MEDIUM_PHONE_SNAPSHOT.deviceId)
        private val DEVICE_LOG = Regex("[^/]+/[^/]+/logcat\\.txt")
    }
}
