package io.technoirlab.device.run.gradle.api

/**
 * How a device is turned while the tests run.
 */
enum class DeviceOrientation {
    /**
     * The device is upright, taller than wide.
     */
    PORTRAIT,

    /**
     * The device is turned sideways, wider than tall.
     */
    LANDSCAPE,
}
