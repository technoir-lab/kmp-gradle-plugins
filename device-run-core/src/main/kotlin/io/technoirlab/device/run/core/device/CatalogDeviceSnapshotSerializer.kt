package io.technoirlab.device.run.core.device

import io.technoirlab.device.run.core.DeviceRunException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Stores device snapshots in a versioned JSON format.
 */
class CatalogDeviceSnapshotSerializer {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Writes [snapshot] to [path], replacing an existing file.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun write(snapshot: CatalogDeviceSnapshot, path: Path) {
        val snapshotFile = SnapshotFile(SCHEMA_VERSION, snapshot.toRecord())
        path.outputStream().use { output -> json.encodeToStream(snapshotFile, output) }
    }

    /**
     * Reads a snapshot written by [write].
     *
     * @throws DeviceRunException when another version of this library wrote the snapshot in a different format.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun read(path: Path): CatalogDeviceSnapshot {
        val element = path.inputStream().use { input -> json.decodeFromStream<JsonElement>(input) }
        val snapshotHeader = json.decodeFromJsonElement<SnapshotHeader>(element)
        if (snapshotHeader.schemaVersion != SCHEMA_VERSION) {
            throw DeviceRunException(
                "The device snapshot $path has format version ${snapshotHeader.schemaVersion}, but this plugin reads version " +
                    "$SCHEMA_VERSION. Run the setup task of the device again.",
            )
        }
        val snapshotFile = json.decodeFromJsonElement<SnapshotFile>(element)
        return snapshotFile.device.toSnapshot()
    }

    /**
     * Returns the name of the snapshot file of the device named [deviceName], `<device name>.json`.
     */
    fun fileName(deviceName: String): String = "$deviceName.json"

    private fun CatalogDeviceSnapshot.toRecord(): DeviceRecord = DeviceRecord(
        deviceId = deviceId,
        displayName = displayName,
        manufacturer = manufacturer,
        physical = physical,
        apiLevel = apiLevel,
        abis = abis,
        screenDensity = screenDensity,
    )

    private fun DeviceRecord.toSnapshot(): CatalogDeviceSnapshot = CatalogDeviceSnapshot(
        deviceId = deviceId,
        displayName = displayName,
        manufacturer = manufacturer,
        physical = physical,
        apiLevel = apiLevel,
        abis = abis,
        screenDensity = screenDensity,
    )

    private companion object {
        private const val SCHEMA_VERSION = 1
    }
}
