package io.technoirlab.device.run.core.testrun.results

import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import java.nio.file.Path

/**
 * The context of converting a finished session into AGP test results.
 *
 * @property jobDirectory the directory of the job below the session directory, named by the job display name.
 * @property resultsDirectory the directory that receives the test results.
 * @property deviceName the name of the device in the DSL.
 * @property reportProperties the properties of the test suite in the JUnit report.
 */
internal data class ResultContext(
    val jobDirectory: String,
    val resultsDirectory: Path,
    val deviceName: String,
    val device: CatalogDeviceSnapshot,
    val reportProperties: Map<String, String>,
)
