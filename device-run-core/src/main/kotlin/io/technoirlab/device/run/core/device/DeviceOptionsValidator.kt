package io.technoirlab.device.run.core.device

import io.technoirlab.device.run.core.InvalidOptionsException
import io.technoirlab.device.run.core.ValidationErrors

/**
 * Validates the options of devices before the catalog is accessed.
 */
internal class DeviceOptionsValidator {
    /**
     * Validates the options of a device.
     *
     * @throws InvalidOptionsException listing every error of the options.
     */
    fun validate(device: DeviceOptions): DeviceSpec {
        val errors = ValidationErrors()
        val property = deviceOptionPath(device.name)
        val deviceId = device.deviceId
        val deviceIdProperty = "$property.deviceId"
        when {
            deviceId.isNullOrBlank() -> errors.add(deviceIdProperty, "is required; set it to the ID of a Device Run catalog device")
            deviceId.any { it == '/' || it.isWhitespace() } -> errors.add(deviceIdProperty, "'$deviceId' is not a catalog device ID")
        }
        val locale = device.locale
        if (locale != null && !LOCALE.matches(locale)) {
            errors.add("$property.locale", "'$locale' must have the form language-region, such as en-US")
        }
        errors.throwIfAny()
        return DeviceSpec(device.name, deviceId.orEmpty(), locale, device.orientation)
    }

    private companion object {
        private val LOCALE = Regex("[A-Za-z]{2,3}-([A-Za-z]{2}|[0-9]{3})")
    }
}
