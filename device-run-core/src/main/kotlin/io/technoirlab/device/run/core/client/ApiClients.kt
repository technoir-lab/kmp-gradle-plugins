package io.technoirlab.device.run.core.client

import com.google.api.client.http.HttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.Sleeper
import com.google.api.services.devicerun.v1alpha.DeviceRun
import com.google.api.services.storage.Storage
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials

/**
 * Creates a Device Run client that authenticates with [credentials] and runs in [location].
 *
 * The client shares [transport] with the credentials and the other API clients of the runner, and identifies its requests by
 * [clientName].
 */
internal fun createDeviceRunClient(
    transport: HttpTransport,
    credentials: GoogleCredentials,
    location: DeviceRunLocation,
    retryPolicy: RequestRetryPolicy,
    clientName: String,
    sleeper: Sleeper = Sleeper.DEFAULT,
): DeviceRun = DeviceRun.Builder(
    transport,
    GsonFactory.getDefaultInstance(),
    requestInitializer(credentials, location, retryPolicy, sleeper),
).setApplicationName(clientName).build()

/**
 * Creates a Cloud Storage client that authenticates with [credentials], bills requests to the project of [location], and identifies
 * them by [clientName].
 */
internal fun createStorageClient(
    transport: HttpTransport,
    credentials: GoogleCredentials,
    location: DeviceRunLocation,
    retryPolicy: RequestRetryPolicy,
    clientName: String,
): Storage = Storage.Builder(
    transport,
    GsonFactory.getDefaultInstance(),
    requestInitializer(credentials, location, retryPolicy, Sleeper.DEFAULT),
).setApplicationName(clientName).build()

private fun requestInitializer(
    credentials: GoogleCredentials,
    location: DeviceRunLocation,
    retryPolicy: RequestRetryPolicy,
    sleeper: Sleeper,
): ApiRequestInitializer = ApiRequestInitializer(
    credentials = HttpCredentialsAdapter(credentials),
    quotaProject = location.projectId,
    retryPolicy = retryPolicy,
    sleeper = sleeper,
)
