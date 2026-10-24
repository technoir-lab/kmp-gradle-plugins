package io.technoirlab.device.run.core.testrun.session

import com.google.api.client.util.Sleeper
import com.google.common.base.Ticker
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Cancels Device Run sessions that a runner abandons.
 *
 * Cancellation is asynchronous: after the request, the session is polled until it finishes or the confirmation budget runs out.
 * Failures are logged and returned rather than thrown, so that they cannot replace the failure that led to the cancellation.
 */
internal class SessionCanceller(
    private val cleanupManager: SessionManager,
    private val ticker: Ticker,
    private val sleeper: Sleeper,
) {
    private val logger = LoggerFactory.getLogger(SessionCanceller::class.java)

    /**
     * Cancels the session named [sessionName] unless it does not exist or has finished, and waits up to [confirmationTimeout]
     * for it to finish.
     *
     * An interruption ends the cancellation and leaves the interrupt flag set. Requests still run while the flag is set, but
     * without retries or confirmation.
     */
    fun cancel(sessionName: String, confirmationTimeout: Duration): CancellationResult {
        val deadline = Deadline(confirmationTimeout, ticker)
        val result = try {
            requestCancellation(sessionName)
        } catch (e: Exception) {
            logger.warn("Unable to cancel Device Run session {}: {}", sessionName, e.message)
            return CancellationResult.FAILED
        }
        return if (result == CancellationResult.UNCONFIRMED) confirmCancellation(sessionName, deadline) else result
    }

    /**
     * Requests cancellation of a session that may still run, which Device Run either marks as cancelled, leaving the cancellation
     * [unconfirmed][CancellationResult.UNCONFIRMED] until the session finishes, or reports as finished.
     *
     * A reported finish counts as a cancellation: Device Run does not tell whether the request or the session itself finished it,
     * and the session had not finished when its status was read.
     */
    private fun requestCancellation(sessionName: String): CancellationResult {
        val session = cleanupManager.findSession(sessionName, SessionView.BASIC) ?: return CancellationResult.NOT_FOUND
        return when {
            session.statusType() == StatusType.DONE -> CancellationResult.ALREADY_FINISHED
            cleanupManager.cancelSession(sessionName).cancelResult == SESSION_FINISHED -> CancellationResult.CANCELLED
            else -> CancellationResult.UNCONFIRMED
        }
    }

    /**
     * Polls a session whose cancellation Device Run accepted until it finishes or [deadline] passes. A failed poll or an
     * interruption leaves the cancellation unconfirmed.
     */
    private fun confirmCancellation(sessionName: String, deadline: Deadline): CancellationResult = try {
        awaitCancellation(sessionName, deadline)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        CancellationResult.UNCONFIRMED
    } catch (e: Exception) {
        logger.warn("Unable to confirm the cancellation of Device Run session {}: {}", sessionName, e.message)
        CancellationResult.UNCONFIRMED
    }

    private fun awaitCancellation(sessionName: String, deadline: Deadline): CancellationResult {
        while (!deadline.hasPassed()) {
            sleeper.sleep(pollDelay(deadline).toMillis())
            if (cleanupManager.findSession(sessionName, SessionView.BASIC)?.statusType() == StatusType.DONE) {
                return CancellationResult.CANCELLED
            }
        }
        return CancellationResult.UNCONFIRMED
    }

    private companion object {
        private const val SESSION_FINISHED = "SESSION_FINISHED"
    }
}
