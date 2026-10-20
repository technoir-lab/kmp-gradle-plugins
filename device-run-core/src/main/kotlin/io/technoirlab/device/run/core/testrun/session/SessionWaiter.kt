package io.technoirlab.device.run.core.testrun.session

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.util.Sleeper
import com.google.api.services.devicerun.v1alpha.model.Status
import com.google.common.base.Stopwatch
import com.google.common.base.Ticker
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.isTransient
import io.technoirlab.device.run.core.client.reason
import io.technoirlab.device.run.core.testrun.toDisplayString
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Duration

/**
 * Waits for submitted Device Run sessions to finish and reports their progress.
 *
 * Pending, running, suspended, and done sessions are handled explicitly; an unknown status is reported and never treated as
 * completion. A session keeps running while Device Run is unreachable, so polls that fail transiently are repeated rather than
 * ending the wait.
 */
internal class SessionWaiter(
    private val sessionManager: SessionManager,
    private val sleeper: Sleeper,
    private val ticker: Ticker,
) {
    private val logger = LoggerFactory.getLogger(SessionWaiter::class.java)

    /**
     * Polls the session until it is done or [deadline] passes.
     *
     * The creation operation finishes with the session and repeats its progress, so it is polled, when there is one, only while
     * the session is not visible yet; it then reports the progress and the failure of the creation.
     *
     * A poll that fails transiently after the retries of its requests, such as during a network outage, is repeated at the next
     * poll until polls have failed for [POLL_FAILURE_TIMEOUT] in a row. Other failures end the wait.
     *
     * An interruption ends the wait with [InterruptedException], during the delay between polls or after the poll that it
     * interrupts. A poll whose retries it cuts short fails instead, with the interrupt flag set.
     *
     * @throws DeviceRunException when polls fail transiently for [POLL_FAILURE_TIMEOUT].
     */
    fun awaitCompletion(metadata: SessionMetadata, operationName: String?, deadline: Deadline?): WaitResult {
        val progress = Progress(metadata.sessionId)
        val failedPolls = FailedPolls(metadata.sessionId)
        while (deadline == null || !deadline.hasPassed()) {
            try {
                val result = poll(metadata, operationName, progress)
                if (result != null) {
                    return result
                }
                failedPolls.reset()
            } catch (e: IOException) {
                failedPolls.record(e)
            }
            sleeper.sleep(pollDelay(deadline).toMillis())
        }
        return WaitResult.TimedOut
    }

    /**
     * Polls the session, or the creation operation while the session is not visible, and returns how the wait ends, or null while
     * the session runs.
     */
    private fun poll(metadata: SessionMetadata, operationName: String?, progress: Progress): WaitResult? {
        val status = visibleStatus(metadata.sessionName)
        checkNotInterrupted()
        if (status != null) {
            progress.report(status)
            return if (status.statusType == DONE) WaitResult.Finished else null
        }
        val operation = operationName?.let(sessionManager::operation) ?: return null
        checkNotInterrupted()
        if (operation.done == true) {
            val error = operation.error ?: return WaitResult.Finished
            return WaitResult.OperationFailed(error.code, error.message)
        }
        progress.reportCreation(operation.metadata?.get(STATUS_MESSAGE) as? String)
        return null
    }

    /**
     * Returns the status of the session, or null while the session is not visible yet.
     */
    private fun visibleStatus(sessionName: String): Status? =
        sessionManager.findSession(sessionName, SessionView.BASIC)?.sessionReport?.status

    private fun IOException.description(): String = (this as? GoogleJsonResponseException)?.reason() ?: message ?: toString()

    /**
     * Logs the changes of one session's status and progress messages.
     */
    private inner class Progress(
        private val sessionId: String,
    ) {
        private var statusType: String? = null
        private var progressMessages = emptyList<String>()
        private var creationMessage: String? = null

        fun report(status: Status) {
            val messages = status.progressMessages.orEmpty()
            if (status.statusType != statusType) {
                when (status.statusType) {
                    SUSPENDED -> logger.warn("Device Run suspended session {}, usually until quota is available.", sessionId)
                    in KNOWN_STATUS_TYPES -> {}
                    else -> logger.warn("Device Run reports the unknown status {} for session {}.", status.statusType, sessionId)
                }
            }
            if (status.statusType != statusType || messages != progressMessages) {
                val details = if (messages.isEmpty()) "" else ": ${messages.joinToString()}"
                logger.info("Device Run session {} is {}{}", sessionId, status.statusType, details)
            }
            statusType = status.statusType
            progressMessages = messages
        }

        /**
         * Logs a change of the progress message of the creation operation, which reports progress before the session is visible.
         */
        fun reportCreation(message: String?) {
            if (message != null && message != creationMessage) {
                logger.info("Device Run session {}: {}", sessionId, message)
            }
            creationMessage = message
        }
    }

    /**
     * Tracks the polls of one session that fail in a row, and ends the wait when a failure cannot recover or the polls have failed
     * for [POLL_FAILURE_TIMEOUT].
     */
    private inner class FailedPolls(
        private val sessionId: String,
    ) {
        private var sinceFirstFailure: Stopwatch? = null

        fun record(failure: IOException) {
            // An interrupted request fails with the interrupt flag set, which ends the wait like a failure that cannot recover.
            if (Thread.currentThread().isInterrupted || !failure.isTransient()) {
                throw failure
            }
            val failingFor = sinceFirstFailure ?: Stopwatch.createStarted(ticker).also {
                sinceFirstFailure = it
                logger.warn(
                    "Unable to get the status of Device Run session {}: {}. Trying again for up to {}.",
                    sessionId,
                    failure.description(),
                    POLL_FAILURE_TIMEOUT.toDisplayString(),
                )
            }
            if (failingFor.elapsed() >= POLL_FAILURE_TIMEOUT) {
                throw DeviceRunException(
                    "Unable to get the status of Device Run session $sessionId for ${POLL_FAILURE_TIMEOUT.toDisplayString()}: " +
                        failure.description(),
                    failure,
                )
            }
        }

        fun reset() {
            sinceFirstFailure = null
        }
    }

    private companion object {
        private const val DONE = "DONE"
        private const val SUSPENDED = "SUSPENDED"
        private const val STATUS_MESSAGE = "statusMessage"
        private val KNOWN_STATUS_TYPES = setOf("PENDING", "RUNNING", SUSPENDED, DONE)

        // Requests retry for seconds, while network and service outages can last minutes.
        private val POLL_FAILURE_TIMEOUT = Duration.ofMinutes(5)
    }
}
