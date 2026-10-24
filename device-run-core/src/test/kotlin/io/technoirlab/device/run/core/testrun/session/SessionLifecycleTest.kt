package io.technoirlab.device.run.core.testrun.session

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.GenericUrl
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.api.services.devicerun.v1alpha.model.CancelSessionResponse
import com.google.api.services.devicerun.v1alpha.model.GoogleLongrunningOperation
import com.google.api.services.devicerun.v1alpha.model.GoogleRpcStatus
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionReport
import com.google.api.services.devicerun.v1alpha.model.Status
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.VirtualClock
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.FailedResponse
import io.technoirlab.device.run.core.client.InterruptingResponse
import io.technoirlab.device.run.core.client.RecordedHttpRequest
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.RequestRetryPolicy
import io.technoirlab.device.run.core.client.enqueueFixture
import io.technoirlab.device.run.core.client.enqueueJson
import io.technoirlab.device.run.core.client.errorResponse
import io.technoirlab.device.run.core.client.testDeviceRunClient
import io.technoirlab.device.run.core.recordLogs
import io.technoirlab.device.run.core.storage.GcsUri
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.event.Level
import java.io.IOException
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.div

class SessionLifecycleTest {
    private val transport = RecordingHttpTransport()
    private val location = DeviceRunLocation("example-project")
    private val clock = VirtualClock()
    private val logs = recordLogs()
    private val sessionManager = SessionManager(
        testDeviceRunClient(
            transport,
            location,
            RequestRetryPolicy(maxRetries = 0, initialInterval = ONE_SECOND, maxInterval = ONE_SECOND, maxElapsed = ONE_SECOND),
        ),
        location,
    )

    // An interrupted retry stops retrying, so a request that needs a retry fails with the interrupt flag set.
    private val retryInterruptingManager = SessionManager(
        testDeviceRunClient(
            transport,
            location,
            RequestRetryPolicy(maxRetries = 1, initialInterval = ONE_SECOND, maxInterval = ONE_SECOND, maxElapsed = ONE_SECOND),
            sleeper = { throw InterruptedException() },
        ),
        location,
    )
    private val registry = SessionRegistry()
    private val submitter = SessionSubmitter(sessionManager, location, registry)
    private val waiter = SessionWaiter(sessionManager, clock.sleeper, clock.ticker)
    private val canceller = SessionCanceller(sessionManager, clock.ticker, clock.sleeper)
    private val lifecycle = SessionLifecycle(sessionManager, registry, submitter, waiter, canceller, clock.ticker)

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `submits a session once, reports its progress, and returns the full session`() {
        enqueueOperation(done = false)
        enqueueStatus("RUNNING", "Job status: 1 running")
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/library-passed/session-full.json")

        val session = lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile())

        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(session.sessionReport.result.resultType).isEqualTo("PASSED")
        // The creation operation repeats the status of a visible session, so it is not polled.
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_FULL",
        )
        assertThat(metadata).isEqualTo(
            SessionMetadata(
                projectId = "example-project",
                sessionId = "session-${metadata.requestId}",
                requestId = metadata.requestId,
                sessionName = "${location.name}/sessions/session-${metadata.requestId}",
                outputDirectory = OUTPUT_DIRECTORY / "session-${metadata.requestId}",
                operationName = OPERATION,
            ),
        )
        assertThat(metadata.sessionId).matches("[a-z][a-z0-9-]{2,61}[a-z0-9]")
        assertThat(transport.requests.first().query("sessionId")).isEqualTo(metadata.sessionId)
        assertThat(transport.requests.first().query("requestId")).isEqualTo(metadata.requestId)
        assertThat(clock.sleeps).containsExactly(Duration.ofSeconds(10))
        assertThat(logs.entries).contains(
            Level.INFO to "Started Device Run session ${metadata.sessionName}. Its results are stored at " +
                "https://console.cloud.google.com/storage/browser/example-bucket/automation/sessions/${metadata.sessionId}/",
            Level.INFO to "Device Run session ${metadata.sessionId} is RUNNING: Job status: 1 running",
        )
    }

    @Test
    fun `reports the progress of the creation operation until the session is visible`() {
        enqueueOperation(done = false)
        enqueueError(404, "NOT_FOUND")
        enqueueOperation(done = false, statusMessage = "Transferring input files")
        enqueueStatus("RUNNING")
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/library-passed/session-full.json")

        lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile())

        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/$OPERATION",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_FULL",
        )
        assertThat(logs.entries).contains(Level.INFO to "Device Run session ${metadata.sessionId}: Transferring input files")
    }

    @Test
    fun `follows an existing session when the creation response is lost`() {
        transport.responses.add(FailedResponse(IOException("Connection reset")))
        enqueueStatus("PENDING")
        enqueueStatus("RUNNING")
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/library-passed/session-full.json")

        lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile())

        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(transport.requests.map { it.method }).containsExactly("POST", "GET", "GET", "GET", "GET")
        assertThat(metadata.operationName).isNull()
        assertThat(logs.entries).contains(
            Level.WARN to "Device Run did not confirm the creation of session ${metadata.sessionName}: Connection reset",
        )
    }

    @Test
    fun `repeats a lost creation with the same IDs only when the session is missing`() {
        transport.responses.add(FailedResponse(IOException("Connection reset")))
        enqueueError(404, "NOT_FOUND")
        enqueueOperation(done = false)
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/library-passed/session-full.json")

        lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile())

        val creations = transport.requests.filter { it.method == "POST" }
        assertThat(creations).hasSize(2)
        assertThat(creations.map { it.query("sessionId") to it.query("requestId") }.distinct()).hasSize(1)
    }

    @Test
    fun `keeps the server explanation of a rejected request and the metadata written before it`() {
        transport.enqueueFixture("errors/create-invalid-argument.json", statusCode = 400)

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageContaining(
                "400 INVALID_ARGUMENT: Violation in CreateSessionRequest.session.session_config.job_configs[0].settings." +
                    "retry_settings.flaky_test_retry_strategy.flaky_test_attempts: flaky_test_attempts must be between 1 and 5.",
            )
        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(transport.requests).singleElement().satisfies({ request ->
            assertThat(request.query("sessionId")).isEqualTo(metadata.sessionId)
            assertThat(request.query("requestId")).isEqualTo(metadata.requestId)
        })
    }

    @Test
    fun `waits through suspended and unknown states until the session is done`() {
        enqueueOperation(done = false)
        enqueueStatus("SUSPENDED")
        enqueueStatus("DRAINING")
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/test-failure/session-full.json")

        val session = lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile())

        val sessionId = SessionMetadataSerializer().read(metadataFile()).sessionId
        assertThat(session.sessionReport.result.resultType).isEqualTo("FAILED")
        assertThat(logs.entries.filter { it.first == Level.WARN }).containsExactly(
            Level.WARN to "Device Run suspended session $sessionId, usually until quota is available.",
            Level.WARN to "Device Run reports the unknown status DRAINING for session $sessionId.",
        )
    }

    @Test
    fun `reports a failed creation operation separately from failed tests`() {
        enqueueOperation(done = false)
        enqueueError(404, "NOT_FOUND")
        enqueueOperation(done = true, error = GoogleRpcStatus().setCode(9).setMessage("Unable to read the input files"))
        enqueueError(404, "NOT_FOUND")

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith(": 9 Unable to read the input files")
        val sessionName = SessionMetadataSerializer().read(metadataFile()).sessionName
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/$OPERATION",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
        )
    }

    @Test
    fun `cancels a session that does not finish within the wait timeout`() {
        enqueueOperation(done = false)
        repeat(3) { enqueueStatus("RUNNING") }
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, Duration.ofSeconds(25), metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith(
                "did not finish within 25s, set by deviceRun.waitTimeout; Device Run cancelled the session.",
            )
        assertThat(clock.sleeps)
            .containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(5), Duration.ofSeconds(10))
        assertThat(transport.requests.last { it.method == "POST" }.uri.rawPath).endsWith(":cancel")
    }

    @Test
    fun `uses a session that finished as the wait timed out instead of cancelling it`() {
        enqueueOperation(done = false)
        enqueueStatus("RUNNING")
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/library-passed/session-full.json")

        val session = lifecycle.run(Session(), OUTPUT_DIRECTORY, Duration.ofSeconds(5), metadataFile())

        assertThat(session.sessionReport.result.resultType).isEqualTo("PASSED")
        assertThat(transport.requests.filter { it.uri.rawPath.endsWith(":cancel") }).isEmpty()
    }

    @Test
    fun `reports the wait timeout when the cancellation finishes a pending session`() {
        enqueueOperation(done = false)
        enqueueStatus("PENDING")
        enqueueStatus("PENDING")
        enqueueCancellation("SESSION_FINISHED")

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, Duration.ofSeconds(10), metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith(
                "did not finish within 10s, set by deviceRun.waitTimeout; Device Run cancelled the session.",
            )
        // The results of a cancelled session are not requested.
        val sessionName = SessionMetadataSerializer().read(metadataFile()).sessionName
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "POST /v1alpha/$sessionName:cancel",
        )
    }

    @Test
    fun `keeps waiting through polls that fail transiently`() {
        enqueueOperation(done = false)
        transport.responses.add(FailedResponse(IOException("Connection reset")))
        enqueueStatus("RUNNING")
        enqueueError(503, "UNAVAILABLE")
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/library-passed/session-full.json")

        val session = lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile())

        val sessionId = SessionMetadataSerializer().read(metadataFile()).sessionId
        assertThat(session.sessionReport.result.resultType).isEqualTo("PASSED")
        assertThat(transport.requests.filter { it.uri.rawPath.endsWith(":cancel") }).isEmpty()
        assertThat(clock.sleeps).containsExactly(Duration.ofSeconds(10), Duration.ofSeconds(10), Duration.ofSeconds(10))
        assertThat(logs.entries.filter { it.first == Level.WARN }).containsExactly(
            Level.WARN to "Unable to get the status of Device Run session $sessionId: Connection reset. Trying again for up to 5m.",
            Level.WARN to "Unable to get the status of Device Run session $sessionId: 503 UNAVAILABLE: Request failed. " +
                "Trying again for up to 5m.",
        )
    }

    @Test
    fun `cancels the session when polls fail transiently for five minutes`() {
        enqueueOperation(done = false)
        repeat(31) { enqueueError(503, "UNAVAILABLE") }
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith("for 5m: 503 UNAVAILABLE: Request failed")
            .hasCauseInstanceOf(GoogleJsonResponseException::class.java)
        val sessionName = SessionMetadataSerializer().read(metadataFile()).sessionName
        assertThat(transport.responses).isEmpty()
        assertThat(transport.requests.map { it.describe() }).contains("POST /v1alpha/$sessionName:cancel")
        assertThat(logs.entries).contains(Level.INFO to "Waiting for the session failed, so Device Run session $sessionName was cancelled.")
    }

    @Test
    fun `stops repeating failed polls when the wait times out`() {
        enqueueOperation(done = false)
        repeat(3) { transport.responses.add(FailedResponse(IOException("Connection reset"))) }
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, Duration.ofSeconds(25), metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith(
                "did not finish within 25s, set by deviceRun.waitTimeout; Device Run cancelled the session.",
            )
        assertThat(transport.responses).isEmpty()
    }

    @Test
    fun `cancels the session when interrupted and keeps the interrupt flag`() {
        clock.interruptNextSleep()
        enqueueOperation(done = false)
        enqueueStatus("RUNNING")
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        val failure = runCatching { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(failure).isInstanceOf(InterruptedException::class.java)
        assertThat(interrupted).isTrue()
        assertThat(transport.requests.map { it.describe() }).contains("POST /v1alpha/${metadata.sessionName}:cancel")
        assertThat(logs.entries).contains(
            Level.INFO to "The test run was interrupted, so Device Run session ${metadata.sessionName} was cancelled.",
        )
    }

    @Test
    fun `cancels the session when an interrupted poll fails and keeps the interrupt flag`() {
        val waiter = SessionWaiter(retryInterruptingManager, clock.sleeper, clock.ticker)
        val lifecycle = SessionLifecycle(sessionManager, registry, submitter, waiter, canceller, clock.ticker)
        enqueueOperation(done = false)
        enqueueError(503, "UNAVAILABLE")
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        val failure = runCatching { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(failure).isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { assertThat(it.statusCode).isEqualTo(503) }
        assertThat(interrupted).isTrue()
        assertThat(logs.entries).contains(
            Level.INFO to "The test run was interrupted, so Device Run session ${metadata.sessionName} was cancelled.",
        )
    }

    @Test
    fun `cancels the session when a poll returns on an interrupted thread`() {
        enqueueOperation(done = false)
        enqueueStatus("RUNNING", interrupting = true)
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        val failure = runCatching { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(failure).isInstanceOf(InterruptedException::class.java)
        assertThat(interrupted).isTrue()
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
            "POST /v1alpha/${metadata.sessionName}:cancel",
            "GET /v1alpha/${metadata.sessionName}?view=SESSION_VIEW_BASIC",
        )
        // The status of the interrupted poll is not reported.
        assertThat(logs.entries).doesNotContain(Level.INFO to "Device Run session ${metadata.sessionId} is RUNNING")
        assertThat(logs.entries).contains(
            Level.INFO to "The test run was interrupted, so Device Run session ${metadata.sessionName} was cancelled.",
        )
    }

    @Test
    fun `cancels the session when a poll of the creation operation returns on an interrupted thread`() {
        enqueueOperation(done = false)
        enqueueError(404, "NOT_FOUND")
        enqueueOperation(done = false, interrupting = true)
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        val failure = runCatching { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        val sessionName = SessionMetadataSerializer().read(metadataFile()).sessionName
        assertThat(failure).isInstanceOf(InterruptedException::class.java)
        assertThat(interrupted).isTrue()
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/$OPERATION",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "POST /v1alpha/$sessionName:cancel",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
        )
        assertThat(logs.entries).contains(Level.INFO to "The test run was interrupted, so Device Run session $sessionName was cancelled.")
    }

    @Test
    fun `reports a session that finishes on an interrupted thread as an interruption`() {
        enqueueOperation(done = false)
        enqueueStatus("DONE", interrupting = true)
        enqueueStatus("DONE")

        val failure = runCatching { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        val sessionName = SessionMetadataSerializer().read(metadataFile()).sessionName
        assertThat(failure).isInstanceOf(InterruptedException::class.java)
        assertThat(interrupted).isTrue()
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
        )
    }

    @Test
    fun `submits no session on an interrupted thread`() {
        enqueueError(404, "NOT_FOUND")
        Thread.currentThread().interrupt()

        val failure = runCatching { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        val sessionName = SessionMetadataSerializer().read(metadataFile()).sessionName
        assertThat(failure).isInstanceOf(InterruptedException::class.java)
        assertThat(interrupted).isTrue()
        assertThat(transport.requests.map { it.describe() }).containsExactly("GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC")
    }

    @Test
    fun `cancels an interrupted creation without looking up or repeating it`() {
        val submitter = SessionSubmitter(retryInterruptingManager, location, registry)
        val lifecycle = SessionLifecycle(sessionManager, registry, submitter, waiter, canceller, clock.ticker)
        enqueueError(503, "UNAVAILABLE")
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueStatus("DONE")

        val failure = runCatching { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }.exceptionOrNull()
        val interrupted = Thread.interrupted()

        val sessionName = SessionMetadataSerializer().read(metadataFile()).sessionName
        assertThat(failure).isInstanceOf(InterruptedException::class.java).hasCauseInstanceOf(GoogleJsonResponseException::class.java)
        assertThat(interrupted).isTrue()
        assertThat(transport.requests.map { it.describe() }).containsExactly(
            "POST /v1alpha/${location.name}/sessions",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "POST /v1alpha/$sessionName:cancel",
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
        )
    }

    @Test
    fun `keeps the original failure when the cancellation fails`() {
        enqueueOperation(done = false)
        enqueueError(403, "PERMISSION_DENIED")
        enqueueError(503, "UNAVAILABLE")

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }
            .isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { assertThat(it.statusCode).isEqualTo(403) }
        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(logs.entries).contains(
            Level.WARN to "Waiting for the session failed, and Device Run session ${metadata.sessionName} may still run: " +
                "the session could not be cancelled. Cancel it with " +
                "'gcloud beta device-run sessions cancel ${metadata.sessionId} --project=example-project'.",
        )
    }

    @Test
    fun `keeps the original failure when the cancellation response cannot be parsed`() {
        enqueueOperation(done = false)
        enqueueError(403, "PERMISSION_DENIED")
        transport.responses.add(MockLowLevelHttpResponse().setContent("""{"sessionReport": "invalid"}"""))

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile()) }
            .isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { assertThat(it.statusCode).isEqualTo(403) }
        val metadata = SessionMetadataSerializer().read(metadataFile())
        assertThat(logs.entries).contains(
            Level.WARN to "Waiting for the session failed, and Device Run session ${metadata.sessionName} may still run: " +
                "the session could not be cancelled. Cancel it with " +
                "'gcloud beta device-run sessions cancel ${metadata.sessionId} --project=example-project'.",
        )
    }

    @Test
    fun `reports a cancellation that Device Run does not confirm in time`() {
        enqueueOperation(done = false)
        enqueueStatus("RUNNING")
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        repeat(6) { enqueueStatus("RUNNING") }

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, Duration.ofSeconds(10), metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith("cancellation was requested, but Device Run has not confirmed it.")
        assertThat(transport.responses).isEmpty()
    }

    @Test
    fun `reports a requested cancellation as unconfirmed when confirming it fails`() {
        enqueueOperation(done = false)
        enqueueStatus("RUNNING")
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        enqueueError(503, "UNAVAILABLE")

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, Duration.ofSeconds(10), metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith("cancellation was requested, but Device Run has not confirmed it.")
        assertThat(transport.responses).isEmpty()
    }

    @Test
    fun `reports a requested cancellation as unconfirmed when its confirmation cannot be parsed`() {
        enqueueOperation(done = false)
        enqueueStatus("RUNNING")
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        transport.responses.add(MockLowLevelHttpResponse().setContent("""{"sessionReport": "invalid"}"""))

        assertThatThrownBy { lifecycle.run(Session(), OUTPUT_DIRECTORY, Duration.ofSeconds(10), metadataFile()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith("cancellation was requested, but Device Run has not confirmed it.")
        assertThat(transport.responses).isEmpty()
    }

    @Test
    fun `cancels only sessions of this runner that may still run when the runner closes`() {
        enqueueOperation(done = false)
        enqueueStatus("DONE")
        transport.enqueueFixture("sessions/library-passed/session-full.json")
        lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, tempDir / "finished.json")
        val abandonedSession = abandonSession(tempDir / "abandoned.json")
        val requestCount = transport.requests.size
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")

        lifecycle.cancelActiveSessions()

        assertThat(transport.requests.drop(requestCount).map { it.describe() }).containsExactly(
            "GET /v1alpha/$abandonedSession?view=SESSION_VIEW_BASIC",
            "POST /v1alpha/$abandonedSession:cancel",
        )
    }

    @Test
    fun `requests the cancellation of active sessions on an interrupted thread and keeps the interrupt flag`() {
        val sessionName = abandonSession(metadataFile())
        val requestCount = transport.requests.size
        enqueueStatus("RUNNING")
        enqueueCancellation("SESSION_MARKED_AS_CANCELLED")
        Thread.currentThread().interrupt()

        val cancellation = runCatching { lifecycle.cancelActiveSessions() }
        val interrupted = Thread.interrupted()

        cancellation.getOrThrow()
        assertThat(interrupted).isTrue()
        assertThat(transport.requests.drop(requestCount).map { it.describe() }).containsExactly(
            "GET /v1alpha/$sessionName?view=SESSION_VIEW_BASIC",
            "POST /v1alpha/$sessionName:cancel",
        )
    }

    private fun metadataFile(): Path = tempDir / "device-run-session.json"

    /**
     * Runs a session that Device Run creates but whose status request it rejects, and whose cancellation then fails, so the
     * session stays registered as one that may still run. Returns the name of the session.
     */
    private fun abandonSession(metadataFile: Path): String {
        enqueueOperation(done = false)
        enqueueError(403, "PERMISSION_DENIED")
        enqueueError(503, "UNAVAILABLE")
        try {
            lifecycle.run(Session(), OUTPUT_DIRECTORY, waitTimeout = null, metadataFile)
        } catch (_: GoogleJsonResponseException) {
            // The rejected status request ends the wait for the session.
        }
        return SessionMetadataSerializer().read(metadataFile).sessionName
    }

    private fun enqueueOperation(
        done: Boolean,
        error: GoogleRpcStatus? = null,
        statusMessage: String? = null,
        interrupting: Boolean = false,
    ) {
        val operation = GoogleLongrunningOperation()
            .setName(OPERATION)
            .setDone(done)
            .setError(error)
            .setMetadata(statusMessage?.let { mapOf("statusMessage" to it) })
        transport.enqueueJson(operation, response(interrupting))
    }

    private fun enqueueStatus(statusType: String, vararg progressMessages: String, interrupting: Boolean = false) {
        val status = Status().setStatusType(statusType).setProgressMessages(progressMessages.toList().ifEmpty { null })
        val session = Session().setSessionReport(SessionReport().setStatus(status))
        transport.enqueueJson(session, response(interrupting))
    }

    /**
     * Returns a response that arrives after the thread was interrupted when [interrupting] is set.
     */
    private fun response(interrupting: Boolean): MockLowLevelHttpResponse =
        if (interrupting) InterruptingResponse() else MockLowLevelHttpResponse()

    private fun enqueueCancellation(result: String) {
        transport.enqueueJson(CancelSessionResponse().setCancelResult(result))
    }

    private fun enqueueError(statusCode: Int, status: String) {
        transport.responses.add(errorResponse(statusCode, "Request failed", status))
    }

    private fun RecordedHttpRequest.describe(): String {
        val query = uri.rawQuery?.takeIf { method == "GET" }?.let { "?$it" }.orEmpty()
        return "$method ${uri.rawPath}$query"
    }

    private fun RecordedHttpRequest.query(name: String): Any? = GenericUrl(url).getFirst(name)

    private companion object {
        private const val OPERATION = "projects/example-project/locations/global/operations/operation-1791741682367"
        private val OUTPUT_DIRECTORY = GcsUri("example-bucket", "automation/sessions")
        private val ONE_SECOND = Duration.ofSeconds(1)
    }
}
