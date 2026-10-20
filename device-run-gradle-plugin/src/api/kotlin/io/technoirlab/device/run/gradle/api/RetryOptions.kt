package io.technoirlab.device.run.gradle.api

import org.gradle.api.provider.Property

/**
 * Retries of failed tests.
 *
 * Device Run also retries infrastructure failures on its own; these options do not control those retries. A test that fails in
 * some attempts and passes in others is reported as passed and marked as flaky. Whether the test task passes follows the verdict
 * of Device Run, which depends on the [scope].
 */
@DeviceRunDsl
interface RetryOptions {
    /**
     * The total number of attempts, including the first one, between 1 and 5. Defaults to 1, which disables retries.
     */
    val maxAttempts: Property<Int>

    /**
     * Whether the retries of a failed attempt run at the same time on separate devices. Defaults to `false`.
     */
    val parallel: Property<Boolean>

    /**
     * Which tests a retry runs. Defaults to [RetryScope.SHARD].
     */
    val scope: Property<RetryScope>
}
