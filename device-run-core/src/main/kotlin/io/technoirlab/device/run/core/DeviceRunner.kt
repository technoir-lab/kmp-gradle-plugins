package io.technoirlab.device.run.core

import io.technoirlab.device.run.core.client.CloudConnectorImpl
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.files.PreparedFile
import io.technoirlab.device.run.core.files.PushedFileOptions
import io.technoirlab.device.run.core.testrun.RunOptions
import io.technoirlab.device.run.core.testrun.TestRunRequest
import io.technoirlab.device.run.core.testrun.job.ArtifactCapture

/**
 * Runs Android instrumentation tests on Device Run, part of Google's Developer Device Platform, and stores their inputs and
 * outputs in Cloud Storage.
 *
 * A runner owns the state that its test runs share: the HTTP transport, the Application Default Credentials and the Google Cloud
 * project they select, the API clients, and the sessions that it submitted. Options are passed with each operation. Operations
 * can run concurrently, and the state is created once, when an operation first needs Google Cloud, so creating a runner loads no
 * credentials.
 *
 * Operations throw [DeviceRunException] for failures that they explain, such as [InvalidOptionsException] for invalid options, and
 * [java.io.IOException] when credentials cannot be loaded or a request fails.
 */
interface DeviceRunner : AutoCloseable {
    /**
     * Resolves [device] in the Device Run catalog and checks that it can run Android instrumentation tests.
     *
     * The options are validated before the catalog is accessed.
     */
    fun resolveDevice(device: DeviceOptions): CatalogDeviceSnapshot

    /**
     * Checks the Cloud Storage objects of [files], uploads their local files to [bucket] otherwise, and returns the objects to push.
     *
     * [bucket] is required unless [files] is empty. The options are validated before Cloud Storage is accessed. Without files, the
     * call returns an empty list without validating [bucket], accessing Cloud Storage, or loading credentials.
     */
    fun preparePushedFiles(bucket: String?, files: List<PushedFileOptions>): List<PreparedFile>

    /**
     * Runs the tests of [request] in a Device Run session, writes their results, and returns whether they passed.
     *
     * The options are validated before anything is uploaded. The session runs synchronously: the call returns after it finishes
     * and the results are written to [TestRunRequest.resultsDirectory]:
     *
     * - `TEST-<device>.xml`: the JUnit report of the final outcome of each test.
     * - `test-result.pb` and `device-info.pb`: the results and the device that Android Studio displays.
     * - `device-run/`: the outputs of the session. The device logs of a passing session are included only when
     *   [RunOptions.Capture.logcat] is [ArtifactCapture.ALWAYS].
     * - `device-run-session.json`: the project and name of the session, written before the session is submitted.
     *
     * When the thread is interrupted, the session is cancelled and [InterruptedException] is thrown.
     */
    fun runTests(request: TestRunRequest): Boolean

    /**
     * Requests the cancellation of the sessions that may still run and releases the HTTP transport.
     */
    override fun close()

    /**
     * Creates a [DeviceRunner] when its owner first needs one. Tests substitute fake runners.
     */
    fun interface Factory {
        /**
         * Creates a new runner, which the caller closes.
         */
        fun create(): DeviceRunner
    }
}

/**
 * Creates a [DeviceRunner] that authenticates with Application Default Credentials.
 *
 * The runner logs through SLF4J: the progress of sessions at the info level, issues that do not fail an operation at the warn
 * level, and the platform failures of a test run that does not pass, such as infrastructure errors or a missing report, at the
 * error level. Failures that end an operation are thrown rather than logged.
 *
 * @param clientName the name of the client that uses the runner, which identifies it in the `User-Agent` header of
 * Device Run and Cloud Storage requests.
 */
fun DeviceRunner(clientName: String = "Device Runner"): DeviceRunner = DeviceRunnerImpl(CloudConnectorImpl(), clientName)
