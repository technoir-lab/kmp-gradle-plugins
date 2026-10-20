package io.technoirlab.device.run.core

import com.google.api.client.http.HttpTransport
import io.technoirlab.device.run.core.client.StubCloudConnector

/**
 * Returns a runner that connects to Google Cloud as production runners do, but through [transport] and with the credentials of
 * [StubCloudConnector].
 */
internal fun testDeviceRunner(transport: HttpTransport): DeviceRunner = DeviceRunnerImpl(StubCloudConnector(transport), CLIENT_NAME)

private const val CLIENT_NAME = "Device Runner Test"
