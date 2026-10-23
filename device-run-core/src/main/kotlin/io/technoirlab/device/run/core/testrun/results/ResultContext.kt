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
) {
    /**
     * The path of the job report below the session directory, `<job>/junit.xml`, which lists the final outcome of each test.
     */
    val jobReportPath: String
        get() = "$jobDirectory/$JOB_REPORT"

    private companion object {
        private const val JOB_REPORT = "junit.xml"
    }
}
