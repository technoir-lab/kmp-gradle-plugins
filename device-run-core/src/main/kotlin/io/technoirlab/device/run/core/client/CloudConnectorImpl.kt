package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.services.devicerun.v1alpha.DeviceRunScopes
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.GoogleCredentials

/**
 * Authenticates with Application Default Credentials and sends requests through a transport that trusts the certificate
 * authorities of the JVM.
 */
internal class CloudConnectorImpl : CloudConnector {
    override fun createCredentials(transportFactory: HttpTransportFactory): GoogleCredentials =
        CredentialsLoader(System::getenv).load(transportFactory).createScoped(OAUTH_SCOPES)

    override fun createHttpTransport(): HttpTransport = NetHttpTransport()

    private companion object {
        // Device Run and Cloud Storage accept the Cloud Platform scope.
        private val OAUTH_SCOPES = listOf(DeviceRunScopes.CLOUD_PLATFORM)
    }
}
