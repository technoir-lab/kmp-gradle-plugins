package io.technoirlab.device.run.core.testrun.results

import com.google.protobuf.Timestamp
import com.google.testing.platform.proto.api.core.AndroidTestDeviceInfoProto.AndroidTestDeviceInfo
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import io.technoirlab.device.run.core.child
import io.technoirlab.device.run.core.childElements
import org.w3c.dom.Element
import java.nio.file.Path
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.div
import kotlin.io.path.inputStream

/**
 * The results of the tests of the device [deviceName] in [resultsDirectory], as the runner writes them for AGP and Android Studio:
 * the UTP result of the suite, `test-result.pb`, the device information, `device-info.pb`, the JUnit report, `TEST-<device>.xml`,
 * and the session outputs mirrored below `device-run/`.
 */
internal class WrittenResults(
    private val resultsDirectory: Path,
    private val deviceName: String,
) {
    /**
     * The directory of the mirrored session outputs, which keep their paths below the session directory.
     */
    val mirrorDirectory = resultsDirectory / MIRROR_DIRECTORY

    /**
     * The JUnit report of the device, which the runner writes only when the job report could be read.
     */
    val testReportFile = resultsDirectory / "TEST-$deviceName.xml"

    /**
     * Reads the UTP result of the suite.
     */
    fun suiteResult(): TestSuiteResult = (resultsDirectory / TEST_RESULT_FILE).inputStream().use(TestSuiteResult::parseFrom)

    /**
     * Reads the information of the device.
     */
    fun deviceInfo(): AndroidTestDeviceInfo = (resultsDirectory / DEVICE_INFO_FILE).inputStream().use(AndroidTestDeviceInfo::parseFrom)

    /**
     * Parses the JUnit report of the device, and returns its root, the `testsuite` element.
     */
    fun testReport(): Element = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(testReportFile.toFile()).documentElement

    /**
     * Returns the mirrored file of the session output at [relativePath] below the session directory, such as
     * `mediumPhone/junit.xml`.
     */
    fun mirroredFile(relativePath: String): Path = mirrorDirectory / relativePath

    private companion object {
        private const val MIRROR_DIRECTORY = "device-run"
        private const val TEST_RESULT_FILE = "test-result.pb"
        private const val DEVICE_INFO_FILE = "device-info.pb"
    }
}

/**
 * Returns the instant of the timestamp.
 */
internal fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

/**
 * Returns the labels of the files attached to the test, in their order.
 */
internal fun TestResult.artifactLabels(): List<String> = outputArtifactList.map { it.label.label }

/**
 * Returns the path of the device log attached to the test, or null when it has none.
 */
internal fun TestResult.logcatPath(): String? = outputArtifactList.singleOrNull { it.label.label == "logcat" }?.sourcePath?.path

/**
 * Returns the test case [name] of a suite of a JUnit report.
 */
internal fun Element.testCase(name: String): Element = childElements("testcase").single { it.getAttribute("name") == name }

/**
 * Returns the properties of a suite of a JUnit report as pairs of their names and values, in their order.
 */
internal fun Element.properties(): List<Pair<String, String>> =
    child("properties").childElements("property").map { it.getAttribute("name") to it.getAttribute("value") }
