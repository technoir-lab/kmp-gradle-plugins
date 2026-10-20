package io.technoirlab.device.run.core.device

/**
 * The catalog profile of a Device Run device that test runs need to select APKs and report device information.
 *
 * @property deviceId the opaque catalog device ID, which is never parsed.
 * @property displayName the name of the device model in the catalog, or the device ID when the catalog reports none.
 * @property manufacturer the manufacturer of the device, or empty when the catalog reports none.
 * @property physical whether the device is physical rather than virtual, which lowers the shard limit.
 * @property apiLevel the Android API level of the device.
 * @property abis the ABIs that the device supports.
 * @property screenDensity the screen density in dpi, or null when the catalog does not report one.
 */
data class CatalogDeviceSnapshot(
    val deviceId: String,
    val displayName: String,
    val manufacturer: String,
    val physical: Boolean,
    val apiLevel: Int,
    val abis: List<String>,
    val screenDensity: Int?,
)
