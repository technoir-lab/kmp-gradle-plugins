package io.technoirlab.device.run.core

import com.google.api.client.http.HttpTransport
import io.technoirlab.device.run.core.client.StubCloudConnector

/**
 * Returns a runner that connects to Google Cloud as production runners do, but through [transport], with the credentials of
 * [StubCloudConnector], and with [clock], on which waits end at once.
 */
internal fun testDeviceRunner(transport: HttpTransport, clock: VirtualClock = VirtualClock()): DeviceRunner =
    DeviceRunnerImpl(StubCloudConnector(transport), CLIENT_NAME, clock.sleeper, clock.ticker)

private const val CLIENT_NAME = "Device Runner Test"
