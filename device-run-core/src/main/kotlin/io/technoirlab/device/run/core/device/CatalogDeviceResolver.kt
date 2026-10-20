package io.technoirlab.device.run.core.device

import com.google.api.services.devicerun.v1alpha.model.CatalogDevice
import com.google.api.services.devicerun.v1alpha.model.Date
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.DeviceRunLocation
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Resolves devices to Device Run catalog devices that can run Android instrumentation tests.
 *
 * The catalog is queried for one device at a time. Low availability is reported but accepted, because it only means that a
 * session can wait in the queue.
 */
internal class CatalogDeviceResolver(
    private val catalogManager: DeviceCatalogManager,
    private val location: DeviceRunLocation,
) {
    private val logger = LoggerFactory.getLogger(CatalogDeviceResolver::class.java)

    /**
     * Fetches the catalog device of [device] and checks that it can run Android instrumentation tests.
     *
     * @throws DeviceRunException when the catalog has no such device or the device cannot run the tests.
     */
    fun resolve(device: DeviceSpec): CatalogDeviceSnapshot {
        val catalogDevice = catalogManager.device(device.deviceId) ?: throw DeviceRunException(unknownDeviceMessage(device))
        val snapshot = snapshotOf(device, catalogDevice)
        reportLifecycleAndAvailability(device, catalogDevice)
        return snapshot
    }

    private fun snapshotOf(device: DeviceSpec, catalogDevice: CatalogDevice): CatalogDeviceSnapshot {
        val apiLevel = catalogDevice.osVersion?.toIntOrNull()
        val abis = catalogDevice.androidDetails?.supportedAbis.orEmpty()
        val reasons = buildList {
            if (catalogDevice.platform != ANDROID) {
                val platform = catalogDevice.platform ?: "an unknown platform"
                add("The device runs $platform, but the runner runs Android instrumentation tests.")
            }
            if (!catalogDevice.supportsAutomation()) {
                add("The device does not support Device Run automation.")
            }
            val deniedReasons = catalogDevice.accessDeniedReasons.orEmpty()
            if (deniedReasons.isNotEmpty()) {
                add("Project ${location.projectId} cannot use the device: ${deniedReasons.joinToString()}.")
            }
            if (apiLevel == null) {
                add("The catalog reports no Android API level for the device.")
            }
            if (abis.isEmpty()) {
                add("The catalog reports no supported ABIs for the device.")
            }
        }
        if (reasons.isNotEmpty() || apiLevel == null) {
            throw DeviceRunException(
                "Device '${device.name}' cannot run tests on the Device Run device '${device.deviceId}':\n" +
                    reasons.joinToString("\n") { "- $it" },
            )
        }
        return CatalogDeviceSnapshot(
            deviceId = device.deviceId,
            displayName = catalogDevice.displayName.orEmpty().ifEmpty { device.deviceId },
            manufacturer = catalogDevice.manufacturer.orEmpty(),
            physical = catalogDevice.hardwareType == PHYSICAL,
            apiLevel = apiLevel,
            abis = abis,
            screenDensity = catalogDevice.primaryScreen?.densityDpi,
        )
    }

    private fun reportLifecycleAndAvailability(device: DeviceSpec, catalogDevice: CatalogDevice) {
        val lifecycle = catalogDevice.lifecycle
        if (lifecycle?.state == DEPRECATED) {
            val removal = lifecycle.removalDate?.let { " and will be removed on ${it.isoDate()}" }.orEmpty()
            logger.warn(
                "Device Run device '{}' is deprecated{}. Choose another Device Run device for device '{}'.",
                device.deviceId,
                removal,
                device.name,
            )
        }
        val availability = catalogDevice.availability
        if (availability?.capacity in LIMITED_CAPACITY || availability?.available in LIMITED_AVAILABILITY) {
            logger.warn(
                "Device Run device '{}' currently reports {} and {}. Sessions can wait in the queue until a device is free.",
                device.deviceId,
                availability?.capacity,
                availability?.available,
            )
        }
    }

    private fun unknownDeviceMessage(device: DeviceSpec): String {
        val availableDevices = try {
            catalogManager.devices(ANDROID_DEVICES)
                .filter { it.supportsAutomation() && it.accessDeniedReasons.isNullOrEmpty() }
                .map { it.name.substringAfterLast('/') }
                .sorted()
        } catch (e: IOException) {
            logger.info("Unable to list Device Run devices: {}", e.message)
            emptyList()
        }
        val suggestion = availableDevices.takeIf { it.isNotEmpty() }
            ?.let { "\nAndroid devices available for automation: ${it.joinToString()}." }
            .orEmpty()
        return "The Device Run catalog of project ${location.projectId} has no device '${device.deviceId}', " +
            "set by ${deviceOptionPath(device.name)}.deviceId.$suggestion\n" +
            "List the catalog with: gcloud beta device-run devices list --filter=\"platform=ANDROID\""
    }

    private fun CatalogDevice.supportsAutomation(): Boolean = supportedProducts.orEmpty().any { it.automation != null }

    private fun Date.isoDate(): String = listOf(year, month, day).joinToString("-") { it?.toString()?.padStart(2, '0') ?: "?" }

    private companion object {
        private const val ANDROID = "ANDROID"
        private const val PHYSICAL = "PHYSICAL"
        private const val DEPRECATED = "DEPRECATED"
        private const val ANDROID_DEVICES = "platform = \"ANDROID\""
        private val LIMITED_CAPACITY = setOf("CAPACITY_NONE", "CAPACITY_LOW")
        private val LIMITED_AVAILABILITY = setOf("AVAILABILITY_NONE", "AVAILABILITY_LOW")
    }
}
