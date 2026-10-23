package io.technoirlab.device.run.gradle.api

import org.gradle.api.provider.Property

/**
 * Retries of failed tests.
 *
 * Device Run also retries infrastructure failures on its own; these options do not control those retries. A test that fails in
 * some attempts and passes in others is reported as passed and marked as flaky. When Device Run fails the session only because no
 * attempt of a shard passed every test, as it can with [RetryScope.SHARD], the test task passes and warns about it.
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
