package io.technoirlab.device.run.core.device

import com.google.api.services.devicerun.v1alpha.DeviceRun
import com.google.api.services.devicerun.v1alpha.model.CatalogDevice
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.collectPages
import io.technoirlab.device.run.core.client.executeOrNull

/**
 * Reads the devices of the Device Run catalog in [location].
 */
internal class DeviceCatalogManager(
    private val deviceRun: DeviceRun,
    private val location: DeviceRunLocation,
) {
    /**
     * Returns the catalog device with [deviceId], or null when the catalog has no such device.
     */
    fun device(deviceId: String): CatalogDevice? = deviceRun.projects()
        .locations()
        .devices()
        .get(location.deviceName(deviceId))
        .executeOrNull()

    /**
     * Returns the catalog devices that match an AIP-160 [filter], such as `platform = "ANDROID"`.
     */
    fun devices(filter: String): List<CatalogDevice> {
        val request = deviceRun.projects()
            .locations()
            .devices()
            .list(location.name)
            .setFilter(filter)
        return collectPages(
            requestPage = { pageToken -> request.setPageToken(pageToken).execute() },
            items = { it.devices },
            nextPageToken = { it.nextPageToken },
        )
    }
}
