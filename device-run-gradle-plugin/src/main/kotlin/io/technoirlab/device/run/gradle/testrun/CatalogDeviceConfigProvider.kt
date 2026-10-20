package io.technoirlab.device.run.gradle.testrun

import com.android.builder.testing.api.DeviceConfigProvider
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import java.util.Locale

/**
 * Describes a catalog device to AGP's tested APK selection.
 *
 * @param locale the locale that the device switches to, or null to leave language and region unspecified.
 */
internal class CatalogDeviceConfigProvider(
    private val device: CatalogDeviceSnapshot,
    locale: String?,
) : DeviceConfigProvider {
    private val locale = locale?.let(Locale::forLanguageTag)

    override fun getConfigFor(abi: String?): String = requireNotNull(abi)

    /**
     * Returns the screen density in dpi, or -1 when the catalog does not report one.
     */
    override fun getDensity(): Int = device.screenDensity ?: -1

    override fun getLanguage(): String? = locale?.language?.takeIf { it.isNotEmpty() }

    override fun getRegion(): String? = locale?.country?.takeIf { it.isNotEmpty() }

    override fun getAbis(): List<String> = device.abis

    override fun getApiLevel(): Int = device.apiLevel
}
