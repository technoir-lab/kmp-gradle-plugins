package io.technoirlab.device.run.gradle.api

import com.android.build.api.dsl.Device
import org.gradle.api.provider.Property

/**
 * A device that runs tests on a Device Run catalog device.
 */
@DeviceRunDsl
@Suppress("UnstableApiUsage")
interface DeviceRunDevice : Device {
    /**
     * The required ID of a Device Run catalog device, such as `mediumphone-arm-35`.
     *
     * The ID selects a device model and its Android version; the plugin treats it as opaque and reads the API level, ABIs, and
     * screen density from the catalog. To list Android devices, run:
     *
     *     gcloud beta device-run devices list --filter="platform=ANDROID"
     */
    val deviceId: Property<String>

    /**
     * The locale that the device switches to before the tests run, in the form `language-region`, such as `en-US`.
     *
     * Unset keeps the default locale of the device.
     */
    val locale: Property<String>

    /**
     * The orientation of the device while the tests run.
     *
     * Unset keeps the default orientation of the device.
     */
    val orientation: Property<DeviceOrientation>
}
