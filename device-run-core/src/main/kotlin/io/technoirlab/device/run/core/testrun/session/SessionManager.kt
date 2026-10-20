package io.technoirlab.device.run.core.testrun.session

import com.google.api.services.devicerun.v1alpha.DeviceRun
import com.google.api.services.devicerun.v1alpha.model.CancelSessionRequest
import com.google.api.services.devicerun.v1alpha.model.CancelSessionResponse
import com.google.api.services.devicerun.v1alpha.model.GoogleLongrunningOperation
import com.google.api.services.devicerun.v1alpha.model.Session
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.executeOrNull

/**
 * Creates, reads, and cancels Device Run sessions in [location].
 */
internal class SessionManager(
    private val deviceRun: DeviceRun,
    private val location: DeviceRunLocation,
) {
    /**
     * Starts creating a session and returns the long-running operation that finishes with it.
     *
     * Repeating the call with the same [sessionId] and [requestId] returns the original operation while the service still
     * remembers the request ID, so retries cannot create a second session.
     */
    fun createSession(sessionId: String, requestId: String, session: Session): GoogleLongrunningOperation =
        deviceRun.projects().locations().sessions()
            .create(location.name, session)
            .setSessionId(sessionId)
            .setRequestId(requestId)
            .execute()

    /**
     * Returns the long-running operation named [name].
     */
    fun operation(name: String): GoogleLongrunningOperation = deviceRun.projects().locations().operations().get(name).execute()

    /**
     * Returns the session named [name] in the requested [view].
     */
    fun session(name: String, view: SessionView): Session = sessionRequest(name, view).execute()

    /**
     * Returns the session named [name] in the requested [view], or null when it does not exist.
     */
    fun findSession(name: String, view: SessionView): Session? = sessionRequest(name, view).executeOrNull()

    /**
     * Requests asynchronous cancellation of the session named [name]. Finished sessions are not affected.
     */
    fun cancelSession(name: String): CancelSessionResponse =
        deviceRun.projects().locations().sessions().cancel(name, CancelSessionRequest()).execute()

    private fun sessionRequest(name: String, view: SessionView): DeviceRun.Projects.Locations.Sessions.Get =
        deviceRun.projects().locations().sessions().get(name).setView(view.apiValue)
}
