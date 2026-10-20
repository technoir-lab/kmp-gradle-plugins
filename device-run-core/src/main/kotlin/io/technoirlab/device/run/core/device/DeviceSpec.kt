package io.technoirlab.device.run.core.device

/**
 * Validated options of one device.
 *
 * @property deviceId the opaque ID of a Device Run catalog device.
 * @property locale the locale to switch the device to, or null to keep the device default.
 * @property orientation the orientation to set, or null to keep the device default.
 */
internal data class DeviceSpec(
    val name: String,
    val deviceId: String,
    val locale: String?,
    val orientation: DeviceOrientation?,
)
