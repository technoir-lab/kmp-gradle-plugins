package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpTransport
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.GoogleCredentials

/**
 * Creates the HTTP transport and the credentials that a runner shares with its API clients.
 */
internal interface CloudConnector {
    /**
     * Creates the credentials of API requests, which request access tokens through [transportFactory].
     */
    fun createCredentials(transportFactory: HttpTransportFactory): GoogleCredentials

    fun createHttpTransport(): HttpTransport
}
