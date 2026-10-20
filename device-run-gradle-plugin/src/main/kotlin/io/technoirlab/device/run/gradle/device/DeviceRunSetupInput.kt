package io.technoirlab.device.run.gradle.device

import com.android.build.api.instrumentation.manageddevice.DeviceSetupInput
import io.technoirlab.device.run.gradle.DeviceRunBuildService
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import java.io.Serial

@Suppress("UnstableApiUsage")
internal abstract class DeviceRunSetupInput : DeviceSetupInput {
    @get:Input
    abstract val deviceName: Property<String>

    // A missing ID is reported with its DSL property by the setup action.
    @get:Input
    @get:Optional
    abstract val deviceId: Property<String>

    @get:ServiceReference(DeviceRunBuildService.NAME)
    abstract val buildService: Property<DeviceRunBuildService>

    private companion object {
        @Serial
        private const val serialVersionUID = 1806489045017428124L
    }
}
