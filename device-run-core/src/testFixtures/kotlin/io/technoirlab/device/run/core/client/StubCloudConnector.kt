package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpTransport
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import java.util.Date

/**
 * Authenticates with a fixed access token of the quota project [QUOTA_PROJECT_ID] and sends requests through [transport].
 */
internal class StubCloudConnector<T : HttpTransport>(
    val transport: T,
) : CloudConnector {
    override fun createCredentials(transportFactory: HttpTransportFactory): GoogleCredentials = GoogleCredentials
        .create(AccessToken("test-access-token", Date(Long.MAX_VALUE)))
        .createWithQuotaProject(QUOTA_PROJECT_ID)

    override fun createHttpTransport(): T = transport

    companion object {
        /**
         * The quota project of the credentials, in which runners that connect through the connector run their sessions.
         */
        const val QUOTA_PROJECT_ID = "test_quota_project_id"
    }
}
