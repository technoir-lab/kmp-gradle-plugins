package io.technoirlab.device.run.core.client

import com.google.auth.oauth2.GoogleCredentials
import io.technoirlab.device.run.core.DeviceRunException

/**
 * Returns the Google Cloud project that runs and pays for Device Run sessions.
 *
 * The quota project takes precedence, including a `GOOGLE_CLOUD_QUOTA_PROJECT` override applied by
 * [CredentialsLoader]; otherwise the project ID of the credentials is used.
 *
 * @throws DeviceRunException when the credentials name neither project.
 */
internal fun GoogleCredentials.executionProjectId(): String = quotaProjectId?.takeIf { it.isNotBlank() }
    ?: projectId?.takeIf { it.isNotBlank() }
    ?: throw DeviceRunException(
        "Unable to determine the Google Cloud project for Device Run from Application Default Credentials. " +
            "Set a quota project with 'gcloud auth application-default set-quota-project PROJECT_ID', " +
            "set GOOGLE_CLOUD_QUOTA_PROJECT, or use service account credentials that contain a project_id.",
    )
