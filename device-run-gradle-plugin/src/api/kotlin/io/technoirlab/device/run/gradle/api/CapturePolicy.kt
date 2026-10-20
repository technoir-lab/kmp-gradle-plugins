package io.technoirlab.device.run.gradle.api

/**
 * When an artifact is captured from the device.
 */
enum class CapturePolicy {
    /**
     * The artifact is not collected.
     */
    DISABLED,

    /**
     * The artifact is kept when the tests fail.
     */
    ON_FAILURE,

    /**
     * The artifact is always kept.
     */
    ALWAYS,
}
