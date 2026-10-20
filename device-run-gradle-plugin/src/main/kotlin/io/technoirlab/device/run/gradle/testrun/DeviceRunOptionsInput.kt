package io.technoirlab.device.run.gradle.testrun

import io.technoirlab.device.run.core.testrun.RunOptions
import io.technoirlab.device.run.gradle.api.CapturePolicy
import io.technoirlab.device.run.gradle.api.RetryScope
import io.technoirlab.device.run.gradle.api.ShardingStrategy
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import java.time.Duration

/**
 * The Device Run options of the project running a test task.
 *
 * Values that only describe or bound a session, such as its labels and wait timeout, are not inputs of the test results. Options
 * are validated when the test task runs.
 */
internal interface DeviceRunOptionsInput {
    @get:Input
    @get:Optional
    val bucket: Property<String>

    @get:Internal
    val waitTimeout: Property<Duration>

    @get:Input
    @get:Optional
    val instrumentationTimeout: Property<Duration>

    /**
     * The version of Android Test Orchestrator, or [RunOptions.Job.AUTO_ORCHESTRATOR_VERSION] for the default version of Device Run,
     * when the AGP test execution option selects the orchestrator.
     */
    @get:Input
    @get:Optional
    val orchestratorVersion: Property<String>

    /**
     * The Android Test Orchestrator and test services APKs from `androidTestUtil`, whose packages Device Run supplies itself.
     */
    @get:Internal
    val suppliedHelperApks: ConfigurableFileCollection

    @get:Internal
    val labels: MapProperty<String, String>

    @get:Input
    @get:Optional
    val shardingStrategy: Property<ShardingStrategy>

    @get:Input
    @get:Optional
    val shardCount: Property<Int>

    @get:Input
    @get:Optional
    val shardTargetDuration: Property<Duration>

    @get:Input
    @get:Optional
    val timingRecordName: Property<String>

    @get:Input
    @get:Optional
    val maxShardCount: Property<Int>

    @get:Input
    @get:Optional
    val maxAttempts: Property<Int>

    @get:Input
    @get:Optional
    val parallelRetries: Property<Boolean>

    @get:Input
    @get:Optional
    val retryScope: Property<RetryScope>

    @get:Input
    val pathsToPull: ListProperty<String>

    @get:Input
    @get:Optional
    val logcat: Property<CapturePolicy>

    @get:Input
    @get:Optional
    val video: Property<CapturePolicy>
}
