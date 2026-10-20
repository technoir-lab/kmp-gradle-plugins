package io.technoirlab.device.run.gradle

import io.technoirlab.device.run.core.RecordingDeviceRunner
import io.technoirlab.device.run.core.StubRunnerFactory
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Provider

/**
 * Registers the build's [DeviceRunBuildService] with a [RecordingDeviceRunner]. Gradle injects the service into the objects
 * that reference it.
 */
internal fun Gradle.registerStubDeviceRunService(): Provider<DeviceRunBuildService> =
    DeviceRunBuildService.registerIfAbsent(this, StubRunnerFactory())
