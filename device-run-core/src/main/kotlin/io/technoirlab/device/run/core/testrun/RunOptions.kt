package io.technoirlab.device.run.core.testrun

import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.job.TestReductionMode
import java.time.Duration

/**
 * The Device Run options of a test run, which are validated before anything is uploaded.
 *
 * Unset values are null or empty. Required values fail the validation when they are unset: the bucket, the shard count of uniform
 * sharding, and the target duration and timing record name of smart sharding. The defaults of [Job], [Retries], and [Capture]
 * apply to their unset values, and the remaining unset values of the job are omitted from Device Run requests, so the service
 * applies its own behavior.
 *
 * @property bucket the required name of an existing Cloud Storage bucket, without the `gs://` scheme, that stores the inputs and
 * outputs of sessions.
 * @property waitTimeout the overall time to wait for the Device Run session of the test run, including queueing, setup, execution,
 * and retries, or null to wait until it finishes.
 * @property job the options of the instrumentation job.
 */
data class RunOptions(
    val bucket: String?,
    val waitTimeout: Duration? = null,
    val job: Job = Job(),
) {
    /**
     * The options of the instrumentation job that runs on the device.
     *
     * @property instrumentationTimeout the maximum time of one instrumentation run, or null for [DEFAULT_INSTRUMENTATION_TIMEOUT].
     * @property orchestratorVersion the version of Android Test Orchestrator, [AUTO_ORCHESTRATOR_VERSION] for the default version of
     * the catalog, or null to run the tests without the orchestrator.
     * @property labels the labels of the job besides the [TestRunRequest.labels] that identify the test run.
     * @property sharding how Device Run splits the job into shards.
     * @property retries the retries of failed tests.
     * @property pathsToPull the absolute device paths of the files and directories pulled after the tests.
     * @property capture the artifacts captured from the device.
     */
    data class Job(
        val instrumentationTimeout: Duration? = null,
        val orchestratorVersion: String? = null,
        val labels: Map<String, String> = emptyMap(),
        val sharding: Sharding = Sharding.None,
        val retries: Retries = Retries(),
        val pathsToPull: List<String> = emptyList(),
        val capture: Capture = Capture(),
    ) {
        companion object {
            /**
             * The instrumentation timeout of jobs that set none.
             */
            val DEFAULT_INSTRUMENTATION_TIMEOUT: Duration = Duration.ofMinutes(5)

            /**
             * The [orchestratorVersion][Job.orchestratorVersion] that selects the default version of Android Test Orchestrator in the
             * Device Run catalog.
             */
            const val AUTO_ORCHESTRATOR_VERSION = "auto"
        }
    }

    /**
     * How Device Run splits the job into shards.
     */
    sealed interface Sharding {
        /**
         * The job runs as a single shard.
         */
        data object None : Sharding

        /**
         * Device Run splits the job into a fixed number of shards with about the same number of tests.
         *
         * @property shardCount the required number of shards.
         */
        data class Uniform(
            val shardCount: Int?,
        ) : Sharding

        /**
         * Device Run splits the job into shards of a target duration, based on the test durations of earlier runs or, without them,
         * 30 seconds per test.
         *
         * @property targetedShardDuration the required target duration of each shard.
         * @property timingRecordName the required basename of the timing record.
         * @property maxShardCount the maximum number of shards, or null to use the Device Run limit of the device.
         */
        data class Smart(
            val targetedShardDuration: Duration?,
            val timingRecordName: String?,
            val maxShardCount: Int? = null,
        ) : Sharding
    }

    /**
     * Retries of failed tests.
     *
     * @property flakyTestAttempts the total number of attempts, including the first one.
     * @property parallelRetry whether the retries of a failed attempt run at the same time on separate devices.
     * @property testReductionMode which tests a retry runs.
     */
    data class Retries(
        val flakyTestAttempts: Int? = null,
        val parallelRetry: Boolean? = null,
        val testReductionMode: TestReductionMode? = null,
    ) {
        companion object {
            /**
             * The number of attempts of jobs that set none, which disables retries.
             */
            const val DEFAULT_FLAKY_TEST_ATTEMPTS = 1

            /**
             * Whether the retries of jobs that do not set it run in parallel.
             */
            const val DEFAULT_PARALLEL_RETRY = false

            /**
             * Which tests the retries of jobs that set none run.
             */
            val DEFAULT_TEST_REDUCTION_MODE = TestReductionMode.NO_REDUCTION
        }
    }

    /**
     * Artifacts captured from the device, which are recorded during the tests and downloaded below `device-run/` in the results
     * directory.
     *
     * @property logcat when the device logs of the executions are kept. Device Run cannot discard them on its own, so with
     * [ArtifactCapture.ON_FAILURE] they are collected and downloaded only when the tests fail.
     * @property video when a screen recording of the run is kept. Device Run decides for each recording, so with
     * [ArtifactCapture.ON_FAILURE] it can keep the recording of a passing run too, for example after a retry.
     */
    data class Capture(
        val logcat: ArtifactCapture? = null,
        val video: ArtifactCapture? = null,
    ) {
        companion object {
            /**
             * When jobs that set none keep the device log.
             */
            val DEFAULT_LOGCAT = ArtifactCapture.ON_FAILURE

            /**
             * When jobs that set none keep a screen recording.
             */
            val DEFAULT_VIDEO = ArtifactCapture.DISABLED
        }
    }
}
