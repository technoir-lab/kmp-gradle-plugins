package io.technoirlab.device.run.core.testrun.job

import io.technoirlab.device.run.core.testrun.sharding.ShardingSpec
import java.time.Duration

/**
 * Validated options of the instrumentation job that runs on the device.
 */
internal data class JobSpec(
    val instrumentationTimeout: Duration,
    val orchestratorVersion: String?,
    val labels: Map<String, String>,
    val sharding: ShardingSpec,
    val retries: RetrySpec,
    val pathsToPull: List<String>,
    val capture: CaptureSpec,
)

/**
 * Retries of failed tests, counted as total attempts including the first one.
 */
internal data class RetrySpec(
    val flakyTestAttempts: Int,
    val parallelRetry: Boolean,
    val testReductionMode: TestReductionMode,
)

/**
 * Artifacts captured from the device.
 */
internal data class CaptureSpec(
    val logcat: ArtifactCapture,
    val video: ArtifactCapture,
)
