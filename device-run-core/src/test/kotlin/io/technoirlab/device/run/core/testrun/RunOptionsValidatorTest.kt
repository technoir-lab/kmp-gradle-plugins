package io.technoirlab.device.run.core.testrun

import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.InvalidOptionsException
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.job.CaptureSpec
import io.technoirlab.device.run.core.testrun.job.JobSpec
import io.technoirlab.device.run.core.testrun.job.RetrySpec
import io.technoirlab.device.run.core.testrun.job.TestReductionMode
import io.technoirlab.device.run.core.testrun.sharding.ShardingSpec
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.time.Duration

class RunOptionsValidatorTest {
    private val validator = RunOptionsValidator()

    @Test
    fun `applies explicit defaults and leaves optional values unset`() {
        val options = RunOptions(bucket = "results-bucket")

        val spec = validator.validate(options)

        assertThat(spec).isEqualTo(
            RunSpec(
                bucket = "results-bucket",
                waitTimeout = null,
                job = JobSpec(
                    instrumentationTimeout = Duration.ofMinutes(5),
                    orchestratorVersion = null,
                    labels = emptyMap(),
                    sharding = ShardingSpec.None,
                    retries = RetrySpec(flakyTestAttempts = 1, parallelRetry = false, testReductionMode = TestReductionMode.NO_REDUCTION),
                    pathsToPull = emptyList(),
                    capture = CaptureSpec(logcat = ArtifactCapture.ON_FAILURE, video = ArtifactCapture.DISABLED),
                ),
            ),
        )
    }

    @Test
    fun `maps configured options to typed values`() {
        val options = RunOptions(
            bucket = "results-bucket",
            waitTimeout = Duration.ofHours(2),
            job = RunOptions.Job(
                instrumentationTimeout = Duration.ofMinutes(20),
                orchestratorVersion = "auto",
                labels = mapOf("suite" to "smoke"),
                sharding = RunOptions.Sharding.Smart(Duration.ofMinutes(5), timingRecordName = "login.v2", maxShardCount = 4),
                retries = RunOptions.Retries(
                    flakyTestAttempts = 3,
                    parallelRetry = true,
                    testReductionMode = TestReductionMode.REDUCE_TO_FAILED_TEST_CASES,
                ),
                pathsToPull = listOf("/sdcard/test-output"),
                capture = RunOptions.Capture(logcat = ArtifactCapture.ALWAYS, video = ArtifactCapture.ON_FAILURE),
            ),
        )

        val spec = validator.validate(options)

        assertThat(spec.waitTimeout).isEqualTo(Duration.ofHours(2))
        assertThat(
            spec.job.sharding,
        ).isEqualTo(ShardingSpec.Smart(Duration.ofMinutes(5), GcsUri("results-bucket", "automation/smart-sharding/login.v2.yaml"), 4))
        assertThat(spec.job.retries).isEqualTo(
            RetrySpec(3, parallelRetry = true, testReductionMode = TestReductionMode.REDUCE_TO_FAILED_TEST_CASES),
        )
        assertThat(spec.job.pathsToPull).containsExactly("/sdcard/test-output")
        assertThat(spec.job.capture).isEqualTo(CaptureSpec(logcat = ArtifactCapture.ALWAYS, video = ArtifactCapture.ON_FAILURE))
    }

    @ParameterizedTest
    @CsvSource("PT1M, PT2M, 1", "PT3H, PT3H, 5")
    fun `accepts durations and attempts at the limits of their ranges`(
        timeout: Duration,
        targetedShardDuration: Duration,
        flakyTestAttempts: Int,
    ) {
        val options = RunOptions(
            bucket = "results-bucket",
            job = RunOptions.Job(
                instrumentationTimeout = timeout,
                sharding = RunOptions.Sharding.Smart(targetedShardDuration, timingRecordName = "login"),
                retries = RunOptions.Retries(flakyTestAttempts = flakyTestAttempts),
            ),
        )

        val spec = validator.validate(options)

        assertThat(spec.job.instrumentationTimeout).isEqualTo(timeout)
        assertThat(spec.job.sharding).isEqualTo(
            ShardingSpec.Smart(
                targetedShardDuration,
                GcsUri("results-bucket", "automation/smart-sharding/login.yaml"),
                maxShardCount = null,
            ),
        )
        assertThat(spec.job.retries.flakyTestAttempts).isEqualTo(flakyTestAttempts)
    }

    @ParameterizedTest
    @CsvSource("1", "200")
    fun `maps uniform sharding to its shard count`(shardCount: Int) {
        val options = RunOptions(
            bucket = "results-bucket",
            job = RunOptions.Job(sharding = RunOptions.Sharding.Uniform(shardCount)),
        )

        val spec = validator.validate(options)

        assertThat(spec.job.sharding).isEqualTo(ShardingSpec.Uniform(shardCount))
    }

    @ParameterizedTest
    @CsvSource(
        ", is required for uniform sharding",
        "0, 'must be between 1 and 200, but was 0'",
        "201, 'must be between 1 and 200, but was 201'",
    )
    fun `uniform sharding needs a shard count within the limit of virtual devices`(shardCount: Int?, error: String) {
        val options = RunOptions(
            bucket = "results-bucket",
            job = RunOptions.Job(sharding = RunOptions.Sharding.Uniform(shardCount)),
        )

        assertThatThrownBy { validator.validate(options) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage("Invalid Device Run configuration:\n- deviceRun.sharding.uniform.shardCount $error.")
    }

    @Test
    fun `smart sharding needs a timing record name`() {
        val options = RunOptions(
            bucket = "results-bucket",
            job = RunOptions.Job(sharding = RunOptions.Sharding.Smart(Duration.ofMinutes(5), timingRecordName = null)),
        )

        assertThatThrownBy { validator.validate(options) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                Invalid Device Run configuration:
                - deviceRun.sharding.smart.timingRecordName is required for smart sharding.
                """.trimIndent(),
            )
    }

    @Test
    fun `reports every invalid value with the DSL property that sets it`() {
        val options = RunOptions(
            bucket = "gs://results-bucket",
            waitTimeout = Duration.ZERO,
            job = RunOptions.Job(
                instrumentationTimeout = Duration.ofSeconds(59),
                sharding = RunOptions.Sharding.Smart(
                    targetedShardDuration = Duration.ofHours(3).plusNanos(1),
                    timingRecordName = "records/login",
                    maxShardCount = 201,
                ),
                retries = RunOptions.Retries(flakyTestAttempts = 6),
            ),
        )

        assertThatThrownBy { validator.validate(options) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                Invalid Device Run configuration:
                - deviceRun.storage.bucket must be a bucket name without the gs:// scheme, but was 'gs://results-bucket'.
                - deviceRun.waitTimeout must be positive, but was 0s.
                - deviceRun.instrumentationTimeout must be between 1m and 3h, but was 59s.
                - deviceRun.sharding.smart.targetDuration must be between 2m and 3h, but was 3h 0m 0.000000001s.
                - deviceRun.sharding.smart.timingRecordName 'records/login' must be a file basename of letters, digits, '.', '_', or '-' without a path or URI.
                - deviceRun.sharding.smart.maxShardCount must be between 1 and 200, but was 201.
                - deviceRun.retries.maxAttempts must be between 1 and 5 total attempts, but was 6.
                """.trimIndent(),
            )
    }

    @Test
    fun `splits runner arguments into a test target per selector value and options`() {
        val spec = validator.validate(RunOptions(bucket = "results-bucket"))
        val arguments = mapOf(
            "size" to "large",
            "class" to "com.example.LoginTest, com.example.SignUpTest",
            "annotation" to "com.example.Smoke",
            "clearPackageData" to "true",
            "listener" to "com.example.TimingListener",
        )

        val split = validator.validateRunnerArguments(spec.job, arguments)

        assertThat(split.testTargets).containsExactly(
            "annotation com.example.Smoke",
            "class com.example.LoginTest",
            "class com.example.SignUpTest",
            "size large",
        )
        assertThat(split.additionalTestOptions).containsExactlyInAnyOrderEntriesOf(
            mapOf("clearPackageData" to "true", "listener" to "com.example.TimingListener"),
        )
    }

    @Test
    fun `rejects runner arguments that Device Run cannot honor`() {
        val spec = validator.validate(RunOptions(bucket = "results-bucket"))
        val arguments = mapOf(
            "testFile" to "/sdcard/tests.txt",
            "notTestFile" to "/sdcard/skip.txt",
            "size" to "huge",
            "class" to "com.example.LoginTest,",
        )

        assertThatThrownBy { validator.validateRunnerArguments(spec.job, arguments) }
            .isInstanceOf(InvalidOptionsException::class.java)
            .hasMessage(
                """
                Invalid instrumentation runner arguments for Device Run:
                - class=com.example.LoginTest, must be a comma-separated list of values.
                - notTestFile=/sdcard/skip.txt filters by test file, which Device Run does not support.
                - size=huge must select size small, medium, large.
                - testFile=/sdcard/tests.txt filters by test file, which Device Run does not support.
                """.trimIndent(),
            )
    }

    @ParameterizedTest
    @MethodSource("managedSharding")
    fun `rejects runner arguments that shard tests when Device Run shards them`(sharding: RunOptions.Sharding) {
        val spec = validator.validate(RunOptions(bucket = "results-bucket", job = RunOptions.Job(sharding = sharding)))
        val arguments = mapOf("numShards" to "2", "shardIndex" to "0")

        assertThatThrownBy { validator.validateRunnerArguments(spec.job, arguments) }
            .isInstanceOf(InvalidOptionsException::class.java)
            .hasMessage(
                """
                Invalid instrumentation runner arguments for Device Run:
                - numShards=2 conflicts with sharding managed by Device Run.
                - shardIndex=0 conflicts with sharding managed by Device Run.
                """.trimIndent(),
            )
    }

    @Test
    fun `user labels cannot use the keys of the test run labels and must leave room for them`() {
        val testRunLabels = mapOf("project" to ":app", "variant" to "debug", "device" to "pixel")
        val labels = (1..13).associate { "label-$it" to "value" } + mapOf("project" to "app", "k".repeat(33) to "v".repeat(1025))
        val options = RunOptions(bucket = "results-bucket", job = RunOptions.Job(labels = labels))

        assertThatThrownBy { validator.validate(options, testRunLabels) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                Invalid Device Run configuration:
                - deviceRun.labels has 15 labels, more than the limit of 13 besides the labels of the test run.
                - deviceRun.labels key 'project' is reserved for the labels of the test run.
                - deviceRun.labels key '${"k".repeat(33)}' is longer than 32 bytes.
                - deviceRun.labels value of '${"k".repeat(33)}' is longer than 1024 bytes.
                """.trimIndent(),
            )
    }

    @Test
    fun `rejects test run labels beyond the Device Run limits`() {
        val testRunLabels = (1..16).associate { "label-$it" to "value" } + mapOf("run" to "v".repeat(1025))
        val options = RunOptions(bucket = "results-bucket")

        assertThatThrownBy { validator.validate(options, testRunLabels) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                Invalid Device Run configuration:
                - TestRunRequest.labels has 17 labels, more than the limit of 16.
                - TestRunRequest.labels value of 'run' is longer than 1024 bytes.
                """.trimIndent(),
            )
    }

    @ParameterizedTest
    @MethodSource("physicalShardLimits")
    fun `physical devices allow fewer shards than virtual devices`(sharding: RunOptions.Sharding, property: String) {
        val options = RunOptions(bucket = "results-bucket", job = RunOptions.Job(sharding = sharding))
        val job = validator.validate(options).job
        val device = MEDIUM_PHONE_SNAPSHOT.copy(deviceId = "tokay-35")

        validator.validateForDevice(job, device)

        assertThatThrownBy { validator.validateForDevice(job, device.copy(physical = true)) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageEndingWith(
                "deviceRun.sharding.$property must be at most 20 for the physical device tokay-35, but was 21.",
            )
    }

    private companion object {
        @JvmStatic
        fun managedSharding() = listOf(
            RunOptions.Sharding.Uniform(shardCount = 2),
            RunOptions.Sharding.Smart(Duration.ofMinutes(5), "login"),
        )

        @JvmStatic
        fun physicalShardLimits() = listOf(
            arguments(RunOptions.Sharding.Uniform(shardCount = 21), "uniform.shardCount"),
            arguments(RunOptions.Sharding.Smart(Duration.ofMinutes(5), "login", maxShardCount = 21), "smart.maxShardCount"),
        )
    }
}
