package io.technoirlab.device.run.core.device

/**
 * A device orientation supported by Device Run.
 */
enum class DeviceOrientation(
    internal val apiValue: String,
) {
    /**
     * The device is upright, taller than wide.
     */
    PORTRAIT("portrait"),

    /**
     * The device is turned sideways, wider than tall.
     */
    LANDSCAPE("landscape"),
}
