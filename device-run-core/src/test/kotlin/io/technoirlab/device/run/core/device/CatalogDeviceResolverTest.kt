package io.technoirlab.device.run.core.device

import com.google.api.client.http.GenericUrl
import com.google.api.services.devicerun.v1alpha.model.CatalogDevice
import com.google.api.services.devicerun.v1alpha.model.CatalogDeviceAvailability
import com.google.api.services.devicerun.v1alpha.model.CatalogDeviceStreamingSupport
import com.google.api.services.devicerun.v1alpha.model.CatalogLifecycle
import com.google.api.services.devicerun.v1alpha.model.CatalogListDevicesResponse
import com.google.api.services.devicerun.v1alpha.model.CatalogSupportedProduct
import com.google.api.services.devicerun.v1alpha.model.Date
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.enqueueFixture
import io.technoirlab.device.run.core.client.enqueueJson
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import io.technoirlab.device.run.core.client.testDeviceRunClient
import io.technoirlab.device.run.core.recordLogs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Named
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.event.Level

class CatalogDeviceResolverTest {
    private val transport = RecordingHttpTransport()
    private val location = DeviceRunLocation("example-project")
    private val catalogManager = DeviceCatalogManager(
        testDeviceRunClient(transport, location),
        location,
    )
    private val logs = recordLogs()
    private val resolver = CatalogDeviceResolver(catalogManager, location)
    private val device = DeviceSpec(name = "pixel", deviceId = "mediumphone-arm-35", locale = null, orientation = null)

    @Test
    fun `takes the device profile from catalog fields instead of the device ID`() {
        enqueueDevice { setOsVersion("34") }

        val snapshot = resolver.resolve(device)

        assertThat(snapshot).isEqualTo(
            CatalogDeviceSnapshot(
                deviceId = "mediumphone-arm-35",
                displayName = "Medium Phone, 6.4in/16cm (Arm)",
                manufacturer = "Generic",
                physical = false,
                apiLevel = 34,
                abis = listOf("arm64-v8a"),
                screenDensity = 420,
            ),
        )
        assertThat(transport.requests.single().uri.rawPath)
            .isEqualTo("/v1alpha/projects/example-project/locations/global/devices/mediumphone-arm-35")
        assertThat(logs.entries).isEmpty()
    }

    @ParameterizedTest
    @MethodSource("unusableDevices")
    fun `rejects devices that cannot run Android instrumentation tests`(change: CatalogDevice.() -> Unit, reason: String) {
        enqueueDevice(change)

        assertThatThrownBy { resolver.resolve(device) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage("Device 'pixel' cannot run tests on the Device Run device 'mediumphone-arm-35':\n- $reason")
    }

    @Test
    fun `suggests Android devices that support automation when the catalog has no such device`() {
        transport.enqueueFixture("errors/device-not-found.json", statusCode = 404)
        val devices = listOf(
            catalogDevice().setName("${location.name}/devices/tokay-35"),
            catalogDevice().setName("${location.name}/devices/streaming-only-35").setSupportedProducts(STREAMING_ONLY),
            catalogDevice(),
        )
        transport.enqueueJson(CatalogListDevicesResponse().setDevices(devices))

        assertThatThrownBy { resolver.resolve(device.copy(deviceId = "no-such-device-35")) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                The Device Run catalog of project example-project has no device 'no-such-device-35', set by deviceRun.devices["pixel"].deviceId.
                Android devices available for automation: mediumphone-arm-35, tokay-35.
                List the catalog with: gcloud beta device-run devices list --filter="platform=ANDROID"
                """.trimIndent(),
            )
        assertThat(GenericUrl(transport.requests.last().url).getFirst("filter")).isEqualTo("platform = \"ANDROID\"")
    }

    @Test
    fun `accepts deprecated devices with limited availability after warning`() {
        enqueueDevice {
            setLifecycle(CatalogLifecycle().setState("DEPRECATED").setRemovalDate(Date().setYear(2026).setMonth(12).setDay(1)))
            setAvailability(CatalogDeviceAvailability().setCapacity("CAPACITY_HIGH").setAvailable("AVAILABILITY_NONE"))
        }

        val snapshot = resolver.resolve(device)

        assertThat(snapshot.apiLevel).isEqualTo(35)
        assertThat(logs.entries).containsExactly(
            Level.WARN to
                "Device Run device 'mediumphone-arm-35' is deprecated and will be removed on 2026-12-01. " +
                "Choose another Device Run device for device 'pixel'.",
            Level.WARN to
                "Device Run device 'mediumphone-arm-35' currently reports CAPACITY_HIGH and AVAILABILITY_NONE. " +
                "Sessions can wait in the queue until a device is free.",
        )
    }

    @Test
    fun `checks the catalog again because access can change after setup`() {
        enqueueDevice {}
        enqueueDevice { setAccessDeniedReasons(listOf("EULA_NOT_ACCEPTED")) }

        resolver.resolve(device)

        assertThatThrownBy { resolver.resolve(device) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith("- Project example-project cannot use the device: EULA_NOT_ACCEPTED.")
        assertThat(transport.requests).hasSize(2)
    }

    private fun enqueueDevice(change: CatalogDevice.() -> Unit) {
        transport.enqueueJson(catalogDevice().apply(change))
    }

    private companion object {
        private val STREAMING_ONLY = listOf(CatalogSupportedProduct().setDeviceStreaming(CatalogDeviceStreamingSupport()))

        private fun catalogDevice(): CatalogDevice = parseDeviceRunFixture("catalog/device-mediumphone-arm-35.json")

        private fun change(name: String, change: CatalogDevice.() -> Unit): Named<CatalogDevice.() -> Unit> = Named.of(name, change)

        @JvmStatic
        fun unusableDevices() = listOf(
            arguments(
                change("iOS device") { setPlatform("IOS") },
                "The device runs IOS, but the runner runs Android instrumentation tests.",
            ),
            arguments(
                change("streaming only") { setSupportedProducts(STREAMING_ONLY) },
                "The device does not support Device Run automation.",
            ),
            arguments(
                change("access denied") { setAccessDeniedReasons(listOf("EULA_NOT_ACCEPTED")) },
                "Project example-project cannot use the device: EULA_NOT_ACCEPTED.",
            ),
            arguments(
                change("missing API level") { setOsVersion(null) },
                "The catalog reports no Android API level for the device.",
            ),
            arguments(
                change("missing ABIs") { setAndroidDetails(null) },
                "The catalog reports no supported ABIs for the device.",
            ),
        )
    }
}
