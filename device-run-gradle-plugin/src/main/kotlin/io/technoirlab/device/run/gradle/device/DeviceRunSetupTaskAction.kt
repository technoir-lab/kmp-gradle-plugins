package io.technoirlab.device.run.gradle.device

import com.android.build.api.instrumentation.manageddevice.DeviceSetupTaskAction
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshotSerializer
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.gradle.reportingInvalidOptions
import io.technoirlab.gradle.asPath
import org.gradle.api.file.Directory
import org.gradle.api.problems.Problems
import javax.inject.Inject

/**
 * Checks the catalog device of a device and records its profile, `<device name>.json`, for the test tasks.
 *
 * The setup task of a device runs before each of its test tasks, so test tasks rerun when the profile of the catalog device
 * changes.
 */
@Suppress("UnstableApiUsage")
internal abstract class DeviceRunSetupTaskAction : DeviceSetupTaskAction<DeviceRunSetupInput> {
    @get:Inject
    abstract val problems: Problems

    override fun setup(setupInput: DeviceRunSetupInput, outputDir: Directory) {
        val deviceName = setupInput.deviceName.get()
        val runner = setupInput.buildService.get().runner
        val deviceOptions = DeviceOptions(deviceName, setupInput.deviceId.orNull)
        val snapshot = problems.reporter.reportingInvalidOptions { runner.resolveDevice(deviceOptions) }
        val serializer = CatalogDeviceSnapshotSerializer()
        serializer.write(snapshot, outputDir.file(serializer.fileName(deviceName)).asPath())
    }
}
