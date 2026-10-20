package io.technoirlab.device.run.gradle.api

import org.gradle.api.provider.Property

/**
 * Artifacts captured from the device, which are recorded during the tests and downloaded below `device-run/` in the test results
 * directory.
 */
@DeviceRunDsl
interface CaptureOptions {
    /**
     * When the device log is kept. Defaults to [CapturePolicy.ON_FAILURE].
     *
     * Device Run cannot discard device logs on its own, so with [CapturePolicy.ON_FAILURE] they are recorded but downloaded only
     * when the session does not pass; they remain in the bucket otherwise.
     */
    val logcat: Property<CapturePolicy>

    /**
     * When a screen recording is kept. Defaults to [CapturePolicy.DISABLED].
     *
     * Device Run decides for each recording, so with [CapturePolicy.ON_FAILURE] it can keep the recording of a passing run too,
     * for example one that passed after a retry.
     */
    val video: Property<CapturePolicy>
}
