package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.manageddevice.DeviceTestRunParameters
import com.android.build.api.instrumentation.manageddevice.TestRunData
import org.gradle.api.file.DirectoryProperty

@Suppress("UnstableApiUsage")
internal class StubDeviceTestRunParameters(
    override val deviceInput: DeviceRunTestRunInput,
    override val setupResult: DirectoryProperty,
    override val testRunData: TestRunData,
) : DeviceTestRunParameters<DeviceRunTestRunInput>
