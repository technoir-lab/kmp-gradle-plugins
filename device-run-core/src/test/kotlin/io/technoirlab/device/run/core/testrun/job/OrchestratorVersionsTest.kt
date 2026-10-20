package io.technoirlab.device.run.core.testrun.job

import com.google.api.client.http.GenericUrl
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.deviceRunFixture
import io.technoirlab.device.run.core.client.enqueueFixture
import io.technoirlab.device.run.core.client.testDeviceRunClient
import io.technoirlab.device.run.core.testrun.RunOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class OrchestratorVersionsTest {
    private val transport = RecordingHttpTransport()
    private val location = DeviceRunLocation("example-project")
    private val orchestratorVersions = OrchestratorVersions(
        testDeviceRunClient(transport, location),
        location,
    )
    private val executor = Executors.newFixedThreadPool(2)

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `accepts the default version without querying the catalog`() {
        orchestratorVersions.requireAvailable(RunOptions.Job.AUTO_ORCHESTRATOR_VERSION)

        assertThat(transport.requests).isEmpty()
    }

    @Test
    fun `accepts a version listed on a later catalog page`() {
        transport.enqueueFixture("catalog/orchestrator-versions-page-1.json")
        transport.enqueueFixture("catalog/orchestrator-versions-page-2.json")

        orchestratorVersions.requireAvailable("1.6.1")

        assertThat(transport.requests.map { GenericUrl(it.url) }).satisfiesExactly(
            { url ->
                assertThat(url.getFirst("filter")).isEqualTo(ORCHESTRATOR_FILTER)
                assertThat(url.getFirst("pageToken")).isNull()
            },
            { url ->
                assertThat(url.getFirst("filter")).isEqualTo(ORCHESTRATOR_FILTER)
                assertThat(url.getFirst("pageToken")).isEqualTo("page-2")
            },
        )
    }

    @Test
    fun `rejects versions missing from the catalog and lists the available ones`() {
        transport.enqueueFixture("catalog/orchestrator-versions-page-1.json")
        transport.enqueueFixture("catalog/orchestrator-versions-page-2.json")

        assertThatThrownBy { orchestratorVersions.requireAvailable("1.9.0") }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                "androidx.test:orchestrator:1.9.0 from androidTestUtil is not available in Device Run. " +
                    "Available versions: 1.4.1 (default), 1.6.1.",
            )
    }

    @Test
    fun `reuses the catalog listing for later checks`() {
        transport.enqueueFixture("catalog/orchestrator-versions-page-1.json")
        transport.enqueueFixture("catalog/orchestrator-versions-page-2.json")
        orchestratorVersions.requireAvailable("1.6.1")

        orchestratorVersions.requireAvailable("1.4.1")

        assertThat(transport.requests).hasSize(2)
    }

    @Test
    fun `checks a version without waiting for a catalog listing in progress`() {
        val heldTransport = HeldCatalogTransport()
        val orchestratorVersions = OrchestratorVersions(testDeviceRunClient(heldTransport, location), location)
        val heldCheck = executor.submit { orchestratorVersions.requireAvailable("1.6.1") }
        check(heldTransport.firstRequestStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "The catalog listing did not start" }

        val check = executor.submit { orchestratorVersions.requireAvailable("1.6.1") }
        val checkedWhileHeld = runCatching { check.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }.isSuccess
        heldTransport.releaseFirstRequest()
        heldCheck.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)

        assertThat(checkedWhileHeld).isTrue()
    }

    /**
     * Answers catalog listings page by page and holds the first request until [releaseFirstRequest].
     */
    private class HeldCatalogTransport : MockHttpTransport() {
        private val firstRequestReleased = CountDownLatch(1)
        private val nextRequestHeld = AtomicBoolean(true)

        val firstRequestStarted = CountDownLatch(1)

        override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
            val held = nextRequestHeld.getAndSet(false)
            val page = if (GenericUrl(url).getFirst("pageToken") == null) 1 else 2
            return object : MockLowLevelHttpRequest(url) {
                override fun execute(): LowLevelHttpResponse {
                    if (held) {
                        firstRequestStarted.countDown()
                        check(firstRequestReleased.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "The catalog listing was not released" }
                    }
                    return MockLowLevelHttpResponse()
                        .setContentType("application/json")
                        .setContent(deviceRunFixture("catalog/orchestrator-versions-page-$page.json"))
                }
            }
        }

        fun releaseFirstRequest() {
            firstRequestReleased.countDown()
        }
    }

    private companion object {
        private const val ORCHESTRATOR_FILTER = "software_type = \"ANDROIDX_TEST_ORCHESTRATOR\""
        private const val TIMEOUT_SECONDS = 10L
    }
}
