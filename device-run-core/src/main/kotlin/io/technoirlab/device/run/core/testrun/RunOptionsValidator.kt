package io.technoirlab.device.run.core.testrun

import io.technoirlab.device.run.core.InvalidOptionsException
import io.technoirlab.device.run.core.ValidationErrors
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import io.technoirlab.device.run.core.device.DeviceOptionsValidator
import io.technoirlab.device.run.core.files.FileOptionsValidator
import io.technoirlab.device.run.core.storage.validateBucket
import io.technoirlab.device.run.core.testrun.job.CaptureSpec
import io.technoirlab.device.run.core.testrun.job.JobLabels
import io.technoirlab.device.run.core.testrun.job.JobSpec
import io.technoirlab.device.run.core.testrun.job.RetrySpec
import io.technoirlab.device.run.core.testrun.job.RunnerArguments
import io.technoirlab.device.run.core.testrun.session.WAIT_TIMEOUT_OPTION
import io.technoirlab.device.run.core.testrun.sharding.ShardingSpec
import io.technoirlab.device.run.core.testrun.sharding.timingRecordUri
import java.time.Duration

/**
 * Validates the options of test runs against the constraints that are known before anything is uploaded.
 *
 * Each error names the DSL property that causes it, and all errors of the options are reported together. Constraints that
 * only the service enforces are left to its `INVALID_ARGUMENT` responses.
 */
internal class RunOptionsValidator {
    private val jobLabels = JobLabels()
    private val runnerArguments = RunnerArguments()
    private val fileValidator = FileOptionsValidator()
    private val deviceValidator = DeviceOptionsValidator()

    /**
     * Validates the options and the device of [request] and splits its runner arguments into test targets and options.
     *
     * @throws InvalidOptionsException when the options, the device, or the runner arguments are invalid.
     */
    fun validate(request: TestRunRequest): TestRunSpec {
        val options = validate(request.options, request.labels)
        val device = deviceValidator.validate(request.device)
        validateForDevice(options.job, request.catalogDevice)
        val runnerArguments = validateRunnerArguments(options.job, request.instrumentationRunnerArguments)
        return TestRunSpec(request, options, device, runnerArguments)
    }

    /**
     * Validates project options and applies their defaults, together with the [testRunLabels] that the job labels leave room for.
     *
     * @throws InvalidOptionsException listing every error of the options and the test run labels.
     */
    fun validate(options: RunOptions, testRunLabels: Map<String, String> = emptyMap()): RunSpec {
        val errors = ValidationErrors()
        val bucket = errors.validateBucket(options.bucket)
        val spec = RunSpec(
            bucket = bucket,
            waitTimeout = errors.waitTimeout(options.waitTimeout),
            job = errors.job(options.job, testRunLabels, bucket),
        )
        errors.throwIfAny()
        return spec
    }

    /**
     * Applies limits that depend on the resolved [catalogDevice].
     *
     * @throws InvalidOptionsException when the job exceeds a limit of the device.
     */
    fun validateForDevice(job: JobSpec, catalogDevice: CatalogDeviceSnapshot) {
        val (property, shardCount) = when (val sharding = job.sharding) {
            ShardingSpec.None -> return
            is ShardingSpec.Smart -> MAX_SHARD_COUNT to (sharding.maxShardCount ?: return)
            is ShardingSpec.Uniform -> SHARD_COUNT to sharding.shardCount
        }
        if (catalogDevice.physical && shardCount > MAX_PHYSICAL_SHARDS) {
            val errors = ValidationErrors()
            errors.add(
                property,
                "must be at most $MAX_PHYSICAL_SHARDS for the physical device ${catalogDevice.deviceId}, but was $shardCount",
            )
            errors.throwIfAny()
        }
    }

    /**
     * Splits the instrumentation runner [arguments] of a test run into test targets and options, which must not shard tests when
     * Device Run shards [job].
     *
     * @throws InvalidOptionsException when Device Run cannot honor a runner argument or the arguments exceed its limits.
     */
    fun validateRunnerArguments(job: JobSpec, arguments: Map<String, String>): RunnerArguments.Split =
        runnerArguments.split(arguments, managedSharding = job.sharding != ShardingSpec.None)

    private fun ValidationErrors.waitTimeout(waitTimeout: Duration?): Duration? {
        if (waitTimeout != null && (waitTimeout.isNegative || waitTimeout.isZero)) {
            add(WAIT_TIMEOUT_OPTION, "must be positive, but was ${waitTimeout.toDisplayString()}")
        }
        return waitTimeout
    }

    private fun ValidationErrors.job(job: RunOptions.Job, testRunLabels: Map<String, String>, bucket: String): JobSpec {
        val instrumentationTimeout = job.instrumentationTimeout ?: RunOptions.Job.DEFAULT_INSTRUMENTATION_TIMEOUT
        return JobSpec(
            instrumentationTimeout = durationInRange(INSTRUMENTATION_TIMEOUT, instrumentationTimeout, MIN_TIMEOUT, MAX_TIMEOUT),
            orchestratorVersion = job.orchestratorVersion,
            labels = labels(job.labels, testRunLabels),
            sharding = sharding(job.sharding, bucket),
            retries = retries(job.retries),
            pathsToPull = fileValidator.validatePathsToPull(job.pathsToPull, errors = this),
            capture = CaptureSpec(
                logcat = job.capture.logcat ?: RunOptions.Capture.DEFAULT_LOGCAT,
                video = job.capture.video ?: RunOptions.Capture.DEFAULT_VIDEO,
            ),
        )
    }

    private fun ValidationErrors.sharding(sharding: RunOptions.Sharding, bucket: String): ShardingSpec = when (sharding) {
        RunOptions.Sharding.None -> ShardingSpec.None
        is RunOptions.Sharding.Smart -> ShardingSpec.Smart(
            targetedShardDuration = targetedShardDuration(sharding.targetedShardDuration),
            timingRecord = timingRecordUri(bucket, timingRecordName(sharding.timingRecordName)),
            maxShardCount = maxShardCount(sharding.maxShardCount),
        )
        is RunOptions.Sharding.Uniform -> ShardingSpec.Uniform(shardCount(sharding.shardCount))
    }

    private fun ValidationErrors.shardCount(shardCount: Int?): Int {
        when (shardCount) {
            null -> add(SHARD_COUNT, "is required for uniform sharding")
            !in 1..MAX_VIRTUAL_SHARDS -> add(SHARD_COUNT, "must be between 1 and $MAX_VIRTUAL_SHARDS, but was $shardCount")
        }
        return shardCount ?: 0
    }

    private fun ValidationErrors.targetedShardDuration(targetedShardDuration: Duration?): Duration {
        if (targetedShardDuration == null) {
            add(TARGET_DURATION, "is required for smart sharding")
            return Duration.ZERO
        }
        return durationInRange(TARGET_DURATION, targetedShardDuration, MIN_SHARD_DURATION, MAX_SHARD_DURATION)
    }

    private fun ValidationErrors.timingRecordName(timingRecordName: String?): String {
        when {
            timingRecordName == null -> add(TIMING_RECORD_NAME, "is required for smart sharding")
            !TIMING_RECORD_NAME_PATTERN.matches(timingRecordName) -> add(
                TIMING_RECORD_NAME,
                "'$timingRecordName' must be a file basename of letters, digits, '.', '_', or '-' without a path or URI",
            )
        }
        return timingRecordName.orEmpty()
    }

    private fun ValidationErrors.maxShardCount(maxShardCount: Int?): Int? {
        if (maxShardCount != null && maxShardCount !in 1..MAX_VIRTUAL_SHARDS) {
            add(MAX_SHARD_COUNT, "must be between 1 and $MAX_VIRTUAL_SHARDS, but was $maxShardCount")
        }
        return maxShardCount
    }

    private fun ValidationErrors.retries(retries: RunOptions.Retries): RetrySpec {
        val flakyTestAttempts = retries.flakyTestAttempts ?: RunOptions.Retries.DEFAULT_FLAKY_TEST_ATTEMPTS
        if (flakyTestAttempts !in 1..MAX_TOTAL_ATTEMPTS) {
            add(MAX_ATTEMPTS, "must be between 1 and $MAX_TOTAL_ATTEMPTS total attempts, but was $flakyTestAttempts")
        }
        return RetrySpec(
            flakyTestAttempts = flakyTestAttempts,
            parallelRetry = retries.parallelRetry ?: RunOptions.Retries.DEFAULT_PARALLEL_RETRY,
            testReductionMode = retries.testReductionMode ?: RunOptions.Retries.DEFAULT_TEST_REDUCTION_MODE,
        )
    }

    private fun ValidationErrors.labels(labels: Map<String, String>, testRunLabels: Map<String, String>): Map<String, String> {
        jobLabels.testRunLabelErrors(testRunLabels).forEach { error -> add(TEST_RUN_LABELS, error) }
        jobLabels.userLabelErrors(labels, testRunLabels).forEach { error -> add(LABELS, error) }
        return labels
    }

    private fun ValidationErrors.durationInRange(property: String, duration: Duration, min: Duration, max: Duration): Duration {
        if (duration !in min..max) {
            add(property, "must be between ${min.toDisplayString()} and ${max.toDisplayString()}, but was ${duration.toDisplayString()}")
        }
        return duration
    }

    private companion object {
        private const val INSTRUMENTATION_TIMEOUT = "deviceRun.instrumentationTimeout"
        private const val LABELS = "deviceRun.labels"
        private const val TEST_RUN_LABELS = "TestRunRequest.labels"
        private const val SHARD_COUNT = "deviceRun.sharding.uniform.shardCount"
        private const val TARGET_DURATION = "deviceRun.sharding.smart.targetDuration"
        private const val TIMING_RECORD_NAME = "deviceRun.sharding.smart.timingRecordName"
        private const val MAX_SHARD_COUNT = "deviceRun.sharding.smart.maxShardCount"
        private const val MAX_ATTEMPTS = "deviceRun.retries.maxAttempts"

        private const val MAX_TOTAL_ATTEMPTS = 5
        private const val MAX_VIRTUAL_SHARDS = 200
        private const val MAX_PHYSICAL_SHARDS = 20

        private val MIN_TIMEOUT = Duration.ofMinutes(1)
        private val MAX_TIMEOUT = Duration.ofHours(3)
        private val MIN_SHARD_DURATION = Duration.ofMinutes(2)
        private val MAX_SHARD_DURATION = Duration.ofHours(3)
        private val TIMING_RECORD_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,199}")
    }
}
