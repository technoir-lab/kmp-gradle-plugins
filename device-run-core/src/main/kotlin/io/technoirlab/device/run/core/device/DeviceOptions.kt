package io.technoirlab.device.run.core.device

/**
 * The options of a device, which are validated before they are used.
 *
 * @property name the name of the device, which identifies it in messages, in the names of its Device Run session and job, and in
 * the results.
 * @property deviceId the required ID of a Device Run catalog device, such as `mediumphone-arm-35`.
 * @property locale the locale that the device switches to, in the form `language-region`, or null to keep the device default.
 * @property orientation the orientation of the device, or null to keep the device default.
 */
data class DeviceOptions(
    val name: String,
    val deviceId: String?,
    val locale: String? = null,
    val orientation: DeviceOrientation? = null,
)
