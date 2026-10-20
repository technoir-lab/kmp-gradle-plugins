package io.technoirlab.device.run.core.testrun.job

/**
 * When a diagnostic artifact of the run, such as the device log or a screen recording, is kept.
 */
enum class ArtifactCapture {
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
