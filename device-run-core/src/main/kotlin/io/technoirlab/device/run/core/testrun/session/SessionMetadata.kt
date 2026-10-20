package io.technoirlab.device.run.core.testrun.session

import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.GcsUriSerializer
import kotlinx.serialization.Serializable

/**
 * Identifies a submitted session, so that it can be found and cancelled manually if the runner stops before the session does.
 *
 * @property projectId the Google Cloud project that runs the session.
 * @property sessionId the ID of the session, chosen before the session is created.
 * @property requestId the ID that makes repeated creation requests return the original operation.
 * @property sessionName the resource name of the session, `projects/<project>/locations/global/sessions/<session id>`.
 * @property outputDirectory the Cloud Storage directory that receives the session outputs.
 * @property operationName the name of the long-running operation that creates the session, or null while it is unknown.
 */
@Serializable
internal data class SessionMetadata(
    val projectId: String,
    val sessionId: String,
    val requestId: String,
    val sessionName: String,
    @Serializable(with = GcsUriSerializer::class)
    val outputDirectory: GcsUri,
    val operationName: String? = null,
)
