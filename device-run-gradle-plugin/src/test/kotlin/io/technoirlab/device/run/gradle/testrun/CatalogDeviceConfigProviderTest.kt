package io.technoirlab.device.run.gradle.testrun

import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CatalogDeviceConfigProviderTest {
    private val snapshot = CatalogDeviceSnapshot(
        deviceId = "mediumphone-x86-34",
        displayName = "Medium Phone",
        manufacturer = "Generic",
        physical = false,
        apiLevel = 34,
        abis = listOf("x86_64", "arm64-v8a"),
        screenDensity = 420,
    )

    @Test
    fun `describes the catalog device and its locale to tested APK selection`() {
        val provider = CatalogDeviceConfigProvider(snapshot, locale = "fr-CA")

        assertThat(provider.abis).containsExactly("x86_64", "arm64-v8a")
        assertThat(provider.density).isEqualTo(420)
        assertThat(provider.apiLevel).isEqualTo(34)
        assertThat(provider.language).isEqualTo("fr")
        assertThat(provider.region).isEqualTo("CA")
    }

    @Test
    fun `leaves an unreported density and an unset locale unspecified`() {
        val device = snapshot.copy(screenDensity = null)
        val provider = CatalogDeviceConfigProvider(device, locale = null)

        assertThat(provider.density).isEqualTo(-1)
        assertThat(provider.language).isNull()
        assertThat(provider.region).isNull()
    }
}
