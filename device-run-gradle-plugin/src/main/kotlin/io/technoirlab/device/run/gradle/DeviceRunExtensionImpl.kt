package io.technoirlab.device.run.gradle

import io.technoirlab.device.run.core.testrun.RunOptions
import io.technoirlab.device.run.gradle.api.DeviceRunExtension
import io.technoirlab.device.run.gradle.api.ShardingStrategy
import io.technoirlab.device.run.gradle.testrun.toCapturePolicy
import io.technoirlab.device.run.gradle.testrun.toRetryScope

internal abstract class DeviceRunExtensionImpl : DeviceRunExtension {
    init {
        instrumentationTimeout.convention(RunOptions.Job.DEFAULT_INSTRUMENTATION_TIMEOUT)
        capture.logcat.convention(RunOptions.Capture.DEFAULT_LOGCAT.toCapturePolicy())
        capture.video.convention(RunOptions.Capture.DEFAULT_VIDEO.toCapturePolicy())
        retries.maxAttempts.convention(RunOptions.Retries.DEFAULT_FLAKY_TEST_ATTEMPTS)
        retries.parallel.convention(RunOptions.Retries.DEFAULT_PARALLEL_RETRY)
        retries.scope.convention(RunOptions.Retries.DEFAULT_TEST_REDUCTION_MODE.toRetryScope())
        sharding.strategy.convention(ShardingStrategy.NONE)
    }

    companion object {
        const val NAME = "deviceRun"
    }
}
