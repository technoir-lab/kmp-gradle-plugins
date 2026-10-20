package io.technoirlab.device.run.core.testrun.results

import com.google.testing.platform.proto.api.core.AndroidTestDeviceInfoProto.AndroidTestDeviceInfo
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import java.nio.file.Path
import kotlin.io.path.outputStream

/**
 * Writes the device information that AGP and Android Studio show with test results.
 */
internal class DeviceInfoWriter {
    /**
     * Writes the information of the catalog [device] used by the device [deviceName] to [path].
     */
    fun write(path: Path, deviceName: String, device: CatalogDeviceSnapshot) {
        val deviceInfo = AndroidTestDeviceInfo.newBuilder()
            .setName(deviceName)
            .setGradleDslDeviceName(deviceName)
            .setManufacturer(device.manufacturer)
            .setModel(device.displayName)
            .setApiLevel(device.apiLevel.toString())
            .addAllAbis(device.abis)
            .build()
        path.outputStream().use { deviceInfo.writeTo(it) }
    }
}
