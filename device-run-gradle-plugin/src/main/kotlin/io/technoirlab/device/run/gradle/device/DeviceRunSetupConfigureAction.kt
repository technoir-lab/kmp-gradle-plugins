package io.technoirlab.device.run.gradle.device

import com.android.build.api.instrumentation.manageddevice.DeviceSetupConfigureAction
import io.technoirlab.device.run.gradle.api.DeviceRunDevice
import io.technoirlab.gradle.setDisallowChanges
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

@Suppress("UnstableApiUsage")
internal open class DeviceRunSetupConfigureAction @Inject constructor(
    private val objectFactory: ObjectFactory,
) : DeviceSetupConfigureAction<DeviceRunDevice, DeviceRunSetupInput> {
    override fun configureTaskInput(deviceDSL: DeviceRunDevice): DeviceRunSetupInput =
        objectFactory.newInstance<DeviceRunSetupInput>().apply {
            configureFrom(deviceDSL)
        }

    private fun DeviceRunSetupInput.configureFrom(device: DeviceRunDevice) {
        deviceName.setDisallowChanges(device.name)
        deviceId.setDisallowChanges(device.deviceId)
    }
}
