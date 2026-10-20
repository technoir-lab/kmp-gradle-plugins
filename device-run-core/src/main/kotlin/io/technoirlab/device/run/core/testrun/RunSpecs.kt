package io.technoirlab.device.run.core.testrun

import io.technoirlab.device.run.core.device.DeviceSpec
import io.technoirlab.device.run.core.testrun.job.JobSpec
import io.technoirlab.device.run.core.testrun.job.RunnerArguments
import java.time.Duration

/**
 * A validated test run: [request] with its validated options and device, and its instrumentation runner arguments split into the
 * test targets and options of Device Run.
 */
internal data class TestRunSpec(
    val request: TestRunRequest,
    val options: RunSpec,
    val device: DeviceSpec,
    val runnerArguments: RunnerArguments.Split,
)

/**
 * Validated Device Run options of a test run, with defaults applied.
 *
 * Optional values of the job that remain null are omitted from Device Run requests, so the service applies its own behavior.
 *
 * @property waitTimeout the overall time to wait for the session, including queueing, setup, execution, and retries, or null to wait
 * until it finishes.
 */
internal data class RunSpec(
    val bucket: String,
    val waitTimeout: Duration?,
    val job: JobSpec,
)
