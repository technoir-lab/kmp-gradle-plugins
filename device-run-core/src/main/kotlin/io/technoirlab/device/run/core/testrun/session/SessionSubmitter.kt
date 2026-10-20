package io.technoirlab.device.run.core.testrun.session

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpStatusCodes
import com.google.api.services.devicerun.v1alpha.model.Session
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.reason
import io.technoirlab.device.run.core.storage.GcsUri
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

/**
 * Creates Device Run sessions under IDs generated before submission, so that an uncertain creation can be resolved without
 * creating a second session.
 */
internal class SessionSubmitter(
    private val sessionManager: SessionManager,
    private val location: DeviceRunLocation,
    private val registry: SessionRegistry,
) {
    private val logger = LoggerFactory.getLogger(SessionSubmitter::class.java)

    private val metadataSerializer = SessionMetadataSerializer()

    /**
     * Generates the session ID and request ID of a new session, records them in [metadataFile], and registers the session as
     * owned by this runner.
     */
    fun prepare(outputDirectory: GcsUri, metadataFile: Path): SessionMetadata {
        val requestId = UUID.randomUUID().toString()
        val sessionId = "$SESSION_ID_PREFIX$requestId"
        val metadata = SessionMetadata(
            projectId = location.projectId,
            sessionId = sessionId,
            requestId = requestId,
            sessionName = location.sessionName(sessionId),
            outputDirectory = outputDirectory / sessionId,
        )
        metadataSerializer.write(metadata, metadataFile)
        registry.add(metadata.sessionName)
        return metadata
    }

    /**
     * Creates the prepared session, records the name of its creation operation in [metadataFile], and returns that name, or null
     * when the session already exists.
     *
     * When the outcome of the request is unknown, the session is looked up, and the request is repeated with the same IDs only
     * if the session is missing; the request ID makes a repeated request return the original operation. An interrupted thread
     * sends none of these requests.
     *
     * @throws DeviceRunException when Device Run rejects the request or does not confirm the creation.
     * @throws InterruptedException when the thread is interrupted before the creation or while it is unconfirmed.
     */
    fun create(metadata: SessionMetadata, session: Session, metadataFile: Path): String? {
        checkNotInterrupted()
        val operationName = when (val attempt = attemptCreation(metadata, session)) {
            is Attempt.Created -> attempt.operationName
            Attempt.AlreadyExists -> null
            is Attempt.Uncertain -> reconcile(metadata, session, attempt.failure)
        }
        if (operationName != null) {
            metadataSerializer.write(metadata.copy(operationName = operationName), metadataFile)
        }
        return operationName
    }

    private fun reconcile(metadata: SessionMetadata, session: Session, failure: IOException): String? {
        checkNotInterrupted(failure)
        logger.warn("Device Run did not confirm the creation of session {}: {}", metadata.sessionName, failure.message)
        if (exists(metadata.sessionName)) {
            return null
        }
        checkNotInterrupted(failure)
        return when (val attempt = attemptCreation(metadata, session)) {
            is Attempt.Created -> attempt.operationName
            Attempt.AlreadyExists -> null
            is Attempt.Uncertain -> throw DeviceRunException(
                "Device Run did not confirm the creation of session ${metadata.sessionName}. Check whether it exists with " +
                    "'gcloud beta device-run sessions describe ${metadata.sessionId} --project=${metadata.projectId}'.",
                attempt.failure,
            ).apply { addSuppressed(failure) }
        }
    }

    private fun attemptCreation(metadata: SessionMetadata, session: Session): Attempt = try {
        Attempt.Created(sessionManager.createSession(metadata.sessionId, metadata.requestId, session).name)
    } catch (e: GoogleJsonResponseException) {
        when (e.statusCode) {
            HttpStatusCodes.STATUS_CODE_CONFLICT -> Attempt.AlreadyExists
            in UNCERTAIN_STATUS_CODES -> Attempt.Uncertain(e)
            else -> {
                // Device Run refused the request, so no session was created.
                registry.remove(metadata.sessionName)
                throw DeviceRunException("Device Run rejected session ${metadata.sessionName}: ${e.reason()}", e)
            }
        }
    } catch (e: IOException) {
        Attempt.Uncertain(e)
    }

    private fun exists(sessionName: String): Boolean = sessionManager.findSession(sessionName, SessionView.BASIC) != null

    /**
     * The outcome of one creation request.
     */
    private sealed interface Attempt {
        data class Created(
            val operationName: String,
        ) : Attempt

        data object AlreadyExists : Attempt

        data class Uncertain(
            val failure: IOException,
        ) : Attempt
    }

    private companion object {
        private const val SESSION_ID_PREFIX = "session-"
        private val UNCERTAIN_STATUS_CODES = (500..599).toSet() + 408
    }
}
