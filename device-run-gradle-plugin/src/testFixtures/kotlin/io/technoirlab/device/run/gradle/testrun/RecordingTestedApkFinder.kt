package io.technoirlab.device.run.gradle.testrun

import com.android.builder.testing.api.DeviceConfigProvider
import java.io.File

internal class RecordingTestedApkFinder(
    private val apks: List<File>,
) : (DeviceConfigProvider) -> List<File> {
    val configurations = mutableListOf<DeviceConfigProvider>()

    override fun invoke(configuration: DeviceConfigProvider): List<File> {
        configurations.add(configuration)
        return apks
    }
}
