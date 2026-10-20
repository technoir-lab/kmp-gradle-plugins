package io.technoirlab.device.run.core.testrun

import io.technoirlab.device.run.core.device.DeviceSpec
import io.technoirlab.device.run.core.files.PreparedFile
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.testrun.apk.TestPackages
import io.technoirlab.device.run.core.testrun.job.RunnerArguments

/**
 * The resolved inputs of a session request besides the validated options.
 *
 * @property testRunnerClass the instrumentation runner class of the test APK.
 * @property runnerArguments the test targets and options passed to the instrumentation runner, split from the runner arguments of
 * the test run.
 * @property testRunName the name of the test run, which the display name of the session starts with.
 * @property labels the labels that identify the test run on its job.
 * @property outputDirectory the `gs://` directory below which Device Run writes session outputs.
 */
internal data class SessionInputs(
    val device: DeviceSpec,
    val packages: TestPackages,
    val pushedFiles: List<PreparedFile>,
    val testRunnerClass: String,
    val runnerArguments: RunnerArguments.Split,
    val testRunName: String,
    val labels: Map<String, String>,
    val outputDirectory: GcsUri,
)
