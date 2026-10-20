package io.technoirlab.device.run.core.testrun.session

import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.common.base.Ticker
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.testrun.toDisplayString
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Duration

/**
 * Runs the Device Run sessions of one runner: submits each session, waits for it, and cancels the sessions that the runner abandons.
 *
 * A session is abandoned when waiting for it times out, fails, or is interrupted, and when the runner closes with the session still
 * active. Only the sessions in [registry], where [submitter] records them, are cancelled. Cancellation is confirmed within its
 * own bounded budget; a session that may still run stays identified by its metadata file for manual cleanup.
 *
 * @param registry the sessions of this runner that may still run, which [submitter] shares.
 */
internal class SessionLifecycle(
    private val sessionManager: SessionManager,
    private val registry: SessionRegistry,
    private val submitter: SessionSubmitter,
    private val waiter: SessionWaiter,
    private val canceller: SessionCanceller,
    private val ticker: Ticker,
) {
    private val logger = LoggerFactory.getLogger(SessionLifecycle::class.java)

    /**
     * Submits [session], waits until it finishes, and returns its full view, whose result decides whether the tests passed.
     *
     * When the thread is interrupted while the session is submitted or awaited, the session is cancelled, and the failure that
     * reports the interruption, an [InterruptedException] or the failure of an interrupted request, is thrown with the interrupt
     * flag set.
     *
     * @param outputDirectory the `gs://` directory of session outputs that [session] configures.
     * @param waitTimeout the overall time to wait, including queueing, setup, execution, and retries, or null to wait indefinitely.
     * @param metadataFile the file that identifies the session while it may run.
     * @throws DeviceRunException when the session cannot be submitted, fails to run, does not finish in time, or Device Run does
     * not report its status for several minutes.
     */
    fun run(session: Session, outputDirectory: GcsUri, waitTimeout: Duration?, metadataFile: Path): Session {
        val deadline = waitTimeout?.let { Deadline(it, ticker) }
        val metadata = submitter.prepare(outputDirectory, metadataFile)
        val result = try {
            val operationName = submitter.create(metadata, session, metadataFile)
            logger.info("Started Device Run session {}. Its results are stored at {}", metadata.sessionName, resultsLink(metadata))
            waiter.awaitCompletion(metadata, operationName, deadline)
        } catch (e: Exception) {
            // Interrupted sleeps throw InterruptedException, while interrupted requests fail with the interrupt flag set.
            if (Thread.interrupted() || e is InterruptedException) {
                // The flag stays clear while the cancellation runs, since it would cut its retries and polls short.
                try {
                    cancelAbandoned(metadata, "The test run was interrupted")
                } finally {
                    Thread.currentThread().interrupt()
                }
            } else {
                cancelAbandoned(metadata, "Waiting for the session failed")
            }
            throw e
        }
        return when (result) {
            WaitResult.Finished -> {
                registry.remove(metadata.sessionName)
                sessionManager.session(metadata.sessionName, SessionView.FULL)
            }
            WaitResult.TimedOut -> {
                val cancellation = cancelAbandoned(metadata, "The session did not finish within the wait timeout")
                if (cancellation != CancellationResult.ALREADY_FINISHED) {
                    throw DeviceRunException(
                        "Device Run session ${metadata.sessionName} did not finish within ${waitTimeout?.toDisplayString()}, " +
                            "set by $WAIT_TIMEOUT_OPTION; ${cancellation.description}.",
                    )
                }
                // The session finished while the wait timed out, so its results are complete.
                sessionManager.session(metadata.sessionName, SessionView.FULL)
            }
            is WaitResult.OperationFailed -> {
                cancelAbandoned(metadata, "The session creation failed")
                throw DeviceRunException("Device Run could not run session ${metadata.sessionName}: ${result.code} ${result.message}")
            }
        }
    }

    /**
     * Requests cancellation of the sessions of this runner that may still run, without waiting for confirmation.
     */
    fun cancelActiveSessions() {
        for (sessionName in registry.activeSessions()) {
            val result = canceller.cancel(sessionName, confirmationTimeout = Duration.ZERO)
            if (result.settled) {
                registry.remove(sessionName)
            }
            logger.info("Cancelling Device Run session {} on shutdown: {}.", sessionName, result.description)
        }
    }

    private fun cancelAbandoned(metadata: SessionMetadata, reason: String): CancellationResult {
        if (metadata.sessionName !in registry) {
            // Device Run rejected the request, so there is no session to cancel.
            return CancellationResult.NOT_FOUND
        }
        val result = canceller.cancel(metadata.sessionName, CONFIRMATION_TIMEOUT)
        if (result.settled) {
            registry.remove(metadata.sessionName)
        }
        when (result) {
            CancellationResult.CANCELLED -> logger.info("{}, so Device Run session {} was cancelled.", reason, metadata.sessionName)
            CancellationResult.ALREADY_FINISHED, CancellationResult.NOT_FOUND ->
                logger.info("{}, but Device Run session {} needs no cancellation: {}.", reason, metadata.sessionName, result.description)
            CancellationResult.UNCONFIRMED, CancellationResult.FAILED -> logger.warn(
                "{}, and Device Run session {} may still run: {}. Cancel it with 'gcloud beta device-run sessions cancel {} --project={}'.",
                reason,
                metadata.sessionName,
                result.description,
                metadata.sessionId,
                metadata.projectId,
            )
        }
        return result
    }

    private fun resultsLink(metadata: SessionMetadata): String =
        "https://console.cloud.google.com/storage/browser/${metadata.outputDirectory.bucket}/${metadata.outputDirectory.objectName}/"

    private companion object {
        private val CONFIRMATION_TIMEOUT = Duration.ofMinutes(1)
    }
}
