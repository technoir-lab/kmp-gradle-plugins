package io.technoirlab.device.run.core.testrun.session

import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionConfig
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.RecordedHttpRequest
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.enqueueFixture
import io.technoirlab.device.run.core.client.testDeviceRunClient
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test

class SessionManagerTest {
    private val transport = RecordingHttpTransport()
    private val location = DeviceRunLocation("execution-project")
    private val manager = SessionManager(testDeviceRunClient(transport, location), location)

    @Test
    fun `addresses sessions and operations in the global location of the execution project`() {
        val sessionName = location.sessionName(SESSION_ID)
        transport.enqueueFixture("sessions/infra-error/operation-created.json")
        transport.enqueueFixture("sessions/infra-error/operation-create-replayed.json")
        transport.enqueueFixture("sessions/infra-error/session-running-basic.json")
        transport.enqueueFixture("sessions/infra-error/session-full.json")
        transport.enqueueFixture("sessions/cancelled/cancel-response.json")

        val operation = manager.createSession(SESSION_ID, REQUEST_ID, Session().setSessionConfig(SessionConfig()))
        manager.operation(operation.name)
        manager.session(sessionName, SessionView.BASIC)
        manager.session(sessionName, SessionView.FULL)
        val cancellation = manager.cancelSession(sessionName)

        assertThat(transport.requests)
            .extracting({ it.method }, { it.uri.rawPath }, { it.query() })
            .containsExactly(
                tuple(
                    "POST",
                    "/v1alpha/projects/execution-project/locations/global/sessions",
                    "requestId=$REQUEST_ID&sessionId=$SESSION_ID",
                ),
                tuple("GET", "/v1alpha/${operation.name}", ""),
                tuple("GET", "/v1alpha/$sessionName", "view=SESSION_VIEW_BASIC"),
                tuple("GET", "/v1alpha/$sessionName", "view=SESSION_VIEW_FULL"),
                tuple("POST", "/v1alpha/$sessionName:cancel", ""),
            )
        assertThat(cancellation.cancelResult).isEqualTo("SESSION_MARKED_AS_CANCELLED")
    }

    private fun RecordedHttpRequest.query(): String = uri.rawQuery.orEmpty()

    private companion object {
        private const val REQUEST_ID = "b7d3bcbe-fd87-410a-bc46-9d4da624c5ea"
        private const val SESSION_ID = "session-$REQUEST_ID"
    }
}
