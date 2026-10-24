package io.technoirlab.device.run.core

import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.files.PreparedFile
import io.technoirlab.device.run.core.files.PushedFileOptions
import io.technoirlab.device.run.core.testrun.TestRunRequest
import io.technoirlab.device.run.core.testrun.results.CapturedScenario

/**
 * Records the operations of a [DeviceRunner] and answers them with configured results instead of accessing Google Cloud.
 *
 * Test runs replay [capturedScenario] through the runner, which writes its results, when it is set, and otherwise return [testsPass]
 * without writing results.
 */
class RecordingDeviceRunner : DeviceRunner {
    val resolvedDevices = mutableListOf<DeviceOptions>()
    val pushedFiles = mutableListOf<PushedFileOptions>()
    val testRuns = mutableListOf<TestRunRequest>()
    var catalogDevice: CatalogDeviceSnapshot? = null
    var capturedScenario: CapturedScenario? = null
    var testsPass = true
    var closeCount = 0
        private set

    override fun resolveDevice(device: DeviceOptions): CatalogDeviceSnapshot {
        resolvedDevices.add(device)
        return checkNotNull(catalogDevice) { "No catalog device configured for ${device.name}" }
    }

    override fun preparePushedFiles(bucket: String?, files: List<PushedFileOptions>): List<PreparedFile> {
        pushedFiles.addAll(files)
        return emptyList()
    }

    override fun runTests(request: TestRunRequest): Boolean {
        testRuns.add(request)
        return capturedScenario?.runTests(request) ?: testsPass
    }

    override fun close() {
        closeCount++
    }
}
