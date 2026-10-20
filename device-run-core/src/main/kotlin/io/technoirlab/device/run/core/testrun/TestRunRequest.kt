package io.technoirlab.device.run.core.testrun

import io.technoirlab.device.run.core.DeviceRunner
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.files.PreparedFile
import java.nio.file.Path

/**
 * The instrumentation tests of one device to run in a Device Run session.
 *
 * @property options the Device Run options of the test run.
 * @property device the device that runs the tests.
 * @property catalogDevice the catalog profile of [device][TestRunRequest.device], as [DeviceRunner.resolveDevice] returned it.
 * @property pushedFiles the files pushed to the device, as [DeviceRunner.preparePushedFiles] returned them.
 * @property testApk the APK that contains the tests.
 * @property testedApks the APKs of the tested application, which install together, such as the split APKs selected for
 * [catalogDevice][TestRunRequest.catalogDevice]; empty when the test APK instruments itself, as for library tests.
 * @property instrumentationRunner the instrumentation runner class of the test APK.
 * @property instrumentationRunnerArguments the instrumentation runner arguments of the test run.
 * @property name the name of the test run, such as `:app freeDebug`, which identifies it in messages and, followed by the name of
 * [device][TestRunRequest.device], names its session.
 * @property labels the labels that identify the test run on its Device Run job; the labels of [RunOptions.Job] must not use their
 * keys.
 * @property reportProperties the properties of the test suite in the JUnit report.
 * @property resultsDirectory the directory that receives the test results.
 */
data class TestRunRequest(
    val options: RunOptions,
    val device: DeviceOptions,
    val catalogDevice: CatalogDeviceSnapshot,
    val pushedFiles: List<PreparedFile>,
    val testApk: Path,
    val testedApks: List<Path>,
    val instrumentationRunner: String,
    val instrumentationRunnerArguments: Map<String, String>,
    val name: String,
    val labels: Map<String, String>,
    val reportProperties: Map<String, String>,
    val resultsDirectory: Path,
)
