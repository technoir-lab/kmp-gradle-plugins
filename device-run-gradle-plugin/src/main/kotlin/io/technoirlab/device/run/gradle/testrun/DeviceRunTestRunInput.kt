package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.manageddevice.DeviceTestRunInput
import io.technoirlab.device.run.gradle.DeviceRunBuildService
import io.technoirlab.device.run.gradle.api.DeviceOrientation
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.services.ServiceReference
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.io.Serial

@Suppress("UnstableApiUsage")
internal abstract class DeviceRunTestRunInput : DeviceTestRunInput {
    @get:Input
    @get:Optional
    abstract val locale: Property<String>

    @get:Input
    @get:Optional
    abstract val orientation: Property<DeviceOrientation>

    /**
     * The name of the root project, which identifies the build in default timing record names.
     */
    @get:Input
    abstract val buildName: Property<String>

    @get:Nested
    abstract val options: DeviceRunOptionsInput

    /**
     * The manifest of the files pushed to the device, whose checksums rerun the tests when a pushed file changes.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pushManifest: RegularFileProperty

    @get:ServiceReference(DeviceRunBuildService.NAME)
    abstract val buildService: Property<DeviceRunBuildService>

    private companion object {
        @Serial
        private const val serialVersionUID = -9126523081763950154L
    }
}
