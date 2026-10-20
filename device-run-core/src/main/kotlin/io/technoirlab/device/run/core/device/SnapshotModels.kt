package io.technoirlab.device.run.core.device

import kotlinx.serialization.Serializable

/**
 * The format version of a device snapshot file.
 */
@Serializable
internal data class SnapshotHeader(
    val schemaVersion: Int,
)

/**
 * The JSON model of a device snapshot file, in a versioned format.
 */
@Serializable
internal data class SnapshotFile(
    val schemaVersion: Int,
    val device: DeviceRecord,
)

/**
 * The JSON model of a [CatalogDeviceSnapshot].
 */
@Serializable
internal data class DeviceRecord(
    val deviceId: String,
    val displayName: String,
    val manufacturer: String,
    val physical: Boolean,
    val apiLevel: Int,
    val abis: List<String>,
    val screenDensity: Int?,
)
