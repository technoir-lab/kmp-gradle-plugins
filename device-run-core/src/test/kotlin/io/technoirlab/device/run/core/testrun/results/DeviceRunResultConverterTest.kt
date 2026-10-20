package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.ExecutionReport
import com.google.api.services.devicerun.v1alpha.model.IssueSummary
import com.google.api.services.devicerun.v1alpha.model.JobReport
import com.google.api.services.devicerun.v1alpha.model.Result
import com.google.api.services.devicerun.v1alpha.model.ResultCause
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionReport
import com.google.api.services.devicerun.v1alpha.model.Warning
import com.google.protobuf.Timestamp
import com.google.testing.platform.proto.api.core.AndroidTestDeviceInfoProto.AndroidTestDeviceInfo
import com.google.testing.platform.proto.api.core.TestResultProto.TestResult
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import io.technoirlab.device.run.core.client.deviceRunFixture
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Element
import java.nio.file.Path
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

class DeviceRunResultConverterTest {
    private val converter = DeviceRunResultConverter()

    @TempDir
    private lateinit var tempDir: Path

    @Test
    fun `reports passing, ignored, assumption-skipped, and parameterized tests of a library`() {
        val artifacts = mirror("library-passed", "library/junit.xml", "library/execution-000/test_result.xml") +
            fakeArtifact("library/execution-000/logcat.txt")

        val passed = converter.convert(session("library-passed"), artifacts, context(jobDirectory = "library"))

        val results = suiteResult()
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(results.testResultList)
            .extracting({ it.testCase.testPackage }, { it.testCase.testClass }, { it.testCase.testMethod }, { it.testStatus })
            .containsExactly(
                tuple(FIXTURE_PACKAGE, "OutcomesTest", "is ignored", TestStatus.SKIPPED),
                tuple(FIXTURE_PACKAGE, "OutcomesTest", "is skipped by assumption", TestStatus.SKIPPED),
                tuple(FIXTURE_PACKAGE, "OutcomesTest", "passes after one second", TestStatus.PASSED),
                tuple(FIXTURE_PACKAGE, "ParameterizedTest", "squares number[0]", TestStatus.PASSED),
                tuple(FIXTURE_PACKAGE, "ParameterizedTest", "squares number[1]", TestStatus.PASSED),
            )
        assertThat(results.testResultList[0].hasError()).isFalse()
        assertThat(results.testResultList[1].error.errorType).isEqualTo("org.junit.AssumptionViolatedException")
        assertThat(results.testResultList[1].error.errorMessage).isEqualTo("Exercises assumption failure reporting")
        assertThat(results.testResultList[1].error.stackTrace).isEqualTo(ASSUMPTION_TRACE)
        assertThat(results.testResultList).allSatisfy { result ->
            assertThat(result.artifactLabels()).containsExactly("device-info", "logcat")
            assertThat(result.testCase.startTime.toInstant()).isEqualTo(Instant.parse("2026-10-11T16:37:33.542Z"))
        }
        val testReport = testReport()
        assertThat(testReport.attributes("tests", "failures", "errors", "skipped", "time")).containsExactly("5", "0", "0", "2", "1.032")
        assertThat(testReport.properties()).containsExactly("device" to "mediumPhone", "flavor" to "", "project" to ":android-library")
        assertThat(deviceInfo().apiLevel).isEqualTo("35")
        assertThat(deviceInfo().abisList).containsExactly("arm64-v8a")
        assertThat(deviceInfo().model).isEqualTo("Medium Phone, 6.4in/16cm (Arm)")
    }

    @Test
    fun `reports a test failure with a single copy of its trace and no platform error`() {
        val session = session("test-failure").apply {
            sessionReport.jobReports.single().setWarnings(
                listOf(Warning().setSummary(IssueSummary().setReason("SLOW_DEVICE").setMessage("The device was slow to boot."))),
            )
        }
        val artifacts = mirror("test-failure", "mediumPhone/junit.xml", "mediumPhone/execution-000/test_result.xml")

        val passed = converter.convert(session, artifacts, context())

        val results = suiteResult()
        val failure = results.testResultList.single { it.testStatus == TestStatus.FAILED }
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(failure.testCase.testMethod).isEqualTo("fails unless reduced to failed tests")
        assertThat(failure.error.errorType).isEqualTo("java.lang.AssertionError")
        assertThat(failure.error.errorMessage).isEqualTo("Fails until a retry runs only failed tests: []")
        assertThat(failure.error.stackTrace).isEqualTo(FAILURE_TRACE)
        assertThat(results.issueList).singleElement().satisfies({ issue ->
            assertThat(issue.name).isEqualTo("SLOW_DEVICE")
            assertThat(issue.message).isEqualTo("The device was slow to boot.")
        })
        val failureElement = testReport().testCase("fails unless reduced to failed tests").child("failure")
        assertThat(failureElement.getAttribute("type")).isEqualTo("java.lang.AssertionError")
        assertThat(failureElement.textContent).isEqualTo(FAILURE_TRACE)
    }

    @Test
    fun `reports a test that ended with an error as a failure that AGP counts, even when Device Run marks it flaky`() {
        val artifacts = fakeArtifact(
            "mediumPhone/junit.xml",
            "<testsuite>" +
                """<testcase classname="a.FirstTest" name="crashes" flaky="true"><error>$CRASH_TRACE</error></testcase>""" +
                """<testcase classname="a.FirstTest" name="runs"/>""" +
                "</testsuite>",
        )

        converter.convert(session("test-failure"), artifacts, context())

        val errored = suiteResult().testResultList.single { it.testCase.testMethod == "crashes" }
        assertThat(errored.testStatus).isEqualTo(TestStatus.ERROR)
        assertThat(errored.error.errorType).isEqualTo("java.lang.IllegalStateException")
        assertThat(errored.error.errorMessage).isEqualTo("Instrumentation crashed")
        assertThat(errored.error.stackTrace).isEqualTo(CRASH_TRACE)
        val testReport = testReport()
        val reportedTest = testReport.testCase("crashes")
        assertThat(testReport.attributes("tests", "failures", "errors")).containsExactly("2", "1", "0")
        assertThat(reportedTest.hasAttribute("flaky")).isFalse()
        assertThat(reportedTest.childElements("error")).isEmpty()
        assertThat(reportedTest.child("failure").attributes("type", "message"))
            .containsExactly("java.lang.IllegalStateException", "Instrumentation crashed")
        assertThat(reportedTest.child("failure").textContent).isEqualTo(CRASH_TRACE)
    }

    @Test
    fun `counts a recovered flaky test as passed while keeping the raw job report`() {
        val artifacts = mirror(
            "flaky-retry-passed",
            "mediumPhone/junit.xml",
            "mediumPhone/execution-000/test_result.xml",
            "mediumPhone/execution-000-rerun-000/test_result.xml",
        ) + fakeArtifact("mediumPhone/execution-000/logcat.txt") + fakeArtifact("mediumPhone/execution-000-rerun-000/logcat.txt")

        val passed = converter.convert(session("flaky-retry-passed"), artifacts, context())

        val results = suiteResult()
        val recovered = results.testResultList.single { it.testCase.testMethod == "fails unless reduced to failed tests" }
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.testResultList).hasSize(7)
        assertThat(recovered.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(recovered.hasError()).isFalse()
        assertThat(recovered.artifactLabels()).containsExactly("device-info")
        assertThat(results.testResultList.filter { it != recovered }).allSatisfy { result ->
            assertThat(result.outputArtifactList.map { it.sourcePath.path }).contains(
                (tempDir / "mirror/mediumPhone/execution-000/logcat.txt").toString(),
            )
        }
        val testReport = testReport()
        assertThat(testReport.attributes("tests", "failures", "skipped")).containsExactly("7", "0", "2")
        assertThat(testReport.testCase("fails unless reduced to failed tests").getElementsByTagName("failure").length).isZero()
        assertThat(tempDir / "mirror/mediumPhone/junit.xml").content().contains("flaky=\"true\">\n    <failure>")
    }

    @Test
    fun `reports infrastructure errors from execution causes without a job report`() {
        val artifacts = fakeArtifact("mediumPhone/execution-000/logcat.txt") +
            fakeArtifact("mediumPhone/execution-000/video-1.webm") +
            fakeArtifact("mediumPhone/execution-000/artifacts/sdcard/test-output/probe-input.txt")

        val passed = converter.convert(session("infra-error"), artifacts, context())

        val results = suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.ERROR)
        assertThat(results.platformError.errorsList.map { it.summary })
            .extracting({ it.errorName }, { it.errorClassification }, { it.errorMessage }, { it.namespace.label })
            .containsExactly(
                tuple(
                    "PLATFORM_INFRASTRUCTURE_ERROR",
                    "INFRA",
                    "Execution execution-000 of job mediumPhone ended with ERROR.",
                    "deviceRun.result",
                ),
            )
        assertThat(results.outputArtifactList.map { it.label.label to it.mimeType }).containsExactly(
            "device-info" to "",
            "logcat" to "text/plain",
            "deviceRun.video" to "video/webm",
            "deviceRun.pulledFile" to "text/plain",
        )
        assertThat(tempDir / "results/TEST-mediumPhone.xml").doesNotExist()
    }

    @ParameterizedTest
    @CsvSource(
        "TIMED_OUT, ERROR, TIMED_OUT, The session ended with TIMED_OUT: The instrumentation timed out.",
        "CANCELLED, CANCELLED, CANCELLED, The session ended with CANCELLED: Execution was cancelled or aborted.",
        "UPCOMING_RESULT, TEST_STATUS_UNSPECIFIED, UPCOMING_RESULT, The session ended with UPCOMING_RESULT: The instrumentation timed out.",
    )
    fun `reports sessions that ended without a test verdict as platform errors`(
        resultType: String,
        expectedStatus: TestStatus,
        classification: String,
        message: String,
    ) {
        val session = if (resultType == "CANCELLED") {
            session("cancelled")
        } else {
            Session().setSessionReport(
                SessionReport().setResult(
                    Result().setResultType(
                        resultType,
                    ).setCause(ResultCause().setSummary(IssueSummary().setMessage("The instrumentation timed out."))),
                ),
            )
        }

        val passed = converter.convert(session, artifacts = emptyList(), context())

        val results = suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(expectedStatus)
        assertThat(results.platformError.errorsList.map { it.summary })
            .extracting({ it.errorClassification }, { it.errorMessage })
            .containsExactly(tuple(classification, message))
    }

    @ParameterizedTest
    @CsvSource(
        "MISSING_JOB_REPORT, ",
        "MALFORMED_JOB_REPORT, <testsuite",
        "MALFORMED_JOB_REPORT, <?xml version=\"1.0\"?><!DOCTYPE testsuite [<!ENTITY secret SYSTEM \"file:///etc/hosts\">]><testsuite/>",
        "NO_TESTS, <testsuite tests=\"0\"/>",
    )
    fun `fails a passing session without a usable job report`(reason: String, jobReport: String?) {
        val artifacts = jobReport?.let { fakeArtifact("library/junit.xml", it) }.orEmpty()

        val passed = converter.convert(session("library-passed"), artifacts, context(jobDirectory = "library"))

        val results = suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.ERROR)
        assertThat(results.platformError.errorsList.map { it.summary.errorName to it.summary.errorClassification })
            .containsExactly(reason to "REPORTING")
    }

    @ParameterizedTest
    @ValueSource(strings = ["failure", "error"])
    fun `fails a passing session whose job report lists a failing test`(outcome: String) {
        val artifacts = fakeArtifact(
            "library/junit.xml",
            """<testsuite><testcase classname="a.FirstTest" name="runs"><$outcome>$CRASH_TRACE</$outcome></testcase></testsuite>""",
        )

        val passed = converter.convert(session("library-passed"), artifacts, context(jobDirectory = "library"))

        val results = suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.hasPlatformError()).isFalse()
    }

    @Test
    fun `attaches to each test of a sharded job the log of its shard`() {
        val artifacts = fakeArtifact("mediumPhone/junit.xml", testSuite("a.FirstTest", "b.SecondTest")) +
            fakeArtifact("mediumPhone/execution-000/test_result.xml", testSuite("a.FirstTest")) +
            fakeArtifact("mediumPhone/execution-000/logcat.txt") +
            fakeArtifact("mediumPhone/execution-001/artifacts/sdcard/logcat.txt") +
            fakeArtifact("mediumPhone/execution-001/test_result.xml", testSuite("b.SecondTest")) +
            fakeArtifact("mediumPhone/execution-001/logcat.txt")

        converter.convert(session("library-passed"), artifacts, context())

        val results = suiteResult()
        assertThat(results.testResultList.associate { it.testCase.testClass to it.logcatPath() }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "FirstTest" to (tempDir / "mirror/mediumPhone/execution-000/logcat.txt").toString(),
                "SecondTest" to (tempDir / "mirror/mediumPhone/execution-001/logcat.txt").toString(),
            ),
        )
        assertThat(results.outputArtifactList.filter { it.label.label == "logcat" }).hasSize(2)
    }

    @Test
    fun `attaches logs to tests of physical devices, whose execution reports are named junit xml`() {
        val artifacts = fakeArtifact("mediumPhone/junit.xml", testSuite("a.FirstTest")) +
            fakeArtifact("mediumPhone/execution-000/junit.xml", "<testsuites>${testSuite("a.FirstTest")}</testsuites>") +
            fakeArtifact("mediumPhone/execution-000/logcat.txt")

        converter.convert(session("library-passed"), artifacts, context())

        val results = suiteResult()
        val logcat = tempDir / "mirror/mediumPhone/execution-000/logcat.txt"
        assertThat(results.testResultList.single().logcatPath()).isEqualTo(logcat.toString())
        assertThat(results.outputArtifactList.map { it.label.label })
            .containsExactly("device-info", "deviceRun.junit", "deviceRun.testResult", "logcat")
    }

    @Test
    fun `starts each test with the only execution that ran it, or else with the session`() {
        val sessionStart = Instant.parse("2026-10-11T18:04:45.328Z")
        val firstShardStart = sessionStart.plusSeconds(10)
        val secondShardStart = sessionStart.plusSeconds(11)
        val session = Session().setSessionReport(
            SessionReport()
                .setStartTime(sessionStart.toString())
                .setResult(Result().setResultType("PASSED"))
                .setJobReports(
                    listOf(
                        JobReport().setDisplayName("mediumPhone").setExecutionReports(
                            listOf(
                                ExecutionReport().setDisplayName("execution-000").setStartTime(firstShardStart.toString()),
                                ExecutionReport().setDisplayName("execution-000-rerun-000")
                                    .setStartTime(sessionStart.plusSeconds(40).toString()),
                                ExecutionReport().setDisplayName("execution-001").setStartTime(secondShardStart.toString()),
                                ExecutionReport().setDisplayName("execution-002").setStartTime("2026-10-11 18:04:57"),
                            ),
                        ),
                    ),
                ),
        )
        val artifacts = fakeArtifact("mediumPhone/junit.xml", testSuite("a.FirstTest", "a.SecondTest", "a.ThirdTest", "a.RetriedTest")) +
            fakeArtifact("mediumPhone/execution-000/test_result.xml", testSuite("a.FirstTest", "a.RetriedTest")) +
            fakeArtifact("mediumPhone/execution-000-rerun-000/test_result.xml", testSuite("a.RetriedTest")) +
            fakeArtifact("mediumPhone/execution-001/test_result.xml", testSuite("a.SecondTest")) +
            fakeArtifact("mediumPhone/execution-002/test_result.xml", testSuite("a.ThirdTest"))

        converter.convert(session, artifacts, context())

        val results = suiteResult()
        assertThat(results.testResultList.map { it.testCase.testClass to it.testCase.startTime.toInstant() }).containsExactly(
            "FirstTest" to firstShardStart,
            "SecondTest" to secondShardStart,
            "ThirdTest" to sessionStart,
            "RetriedTest" to sessionStart,
        )
    }

    @Test
    fun `starts each test with the session when the final report omits the job`() {
        val sessionStart = Instant.parse("2026-10-11T18:04:48.016Z")
        val artifacts = mirror(
            "flaky-retry-passed",
            "mediumPhone/junit.xml",
            "mediumPhone/execution-000/test_result.xml",
            "mediumPhone/execution-000-rerun-000/test_result.xml",
        )

        converter.convert(session("flaky-retry-passed"), artifacts, context())

        val results = suiteResult()
        assertThat(results.testResultList).hasSize(7).allSatisfy { result ->
            assertThat(result.testCase.startTime.toInstant()).isEqualTo(sessionStart)
        }
    }

    private fun session(scenario: String): Session = parseDeviceRunFixture("sessions/$scenario/session-full.json")

    private fun context(jobDirectory: String = "mediumPhone"): ResultContext = ResultContext(
        jobDirectory = jobDirectory,
        resultsDirectory = (tempDir / "results").createDirectories(),
        deviceName = "mediumPhone",
        device = MEDIUM_PHONE_SNAPSHOT,
        reportProperties = mapOf(
            "device" to "mediumPhone",
            "flavor" to "",
            "project" to if (jobDirectory == "library") ":android-library" else ":android-application",
        ),
    )

    private fun mirror(scenario: String, vararg relativePaths: String): List<MirroredArtifact> = relativePaths.flatMap { relativePath ->
        fakeArtifact(relativePath, deviceRunFixture("sessions/$scenario/outputs/$relativePath"))
    }

    private fun fakeArtifact(relativePath: String, content: String = "artifact"): List<MirroredArtifact> {
        val path = (tempDir / "mirror" / relativePath).apply {
            parent.createDirectories()
            writeText(content)
        }
        return listOf(MirroredArtifact(relativePath, path))
    }

    private fun testSuite(vararg classNames: String): String =
        "<testsuite>${classNames.joinToString("") { "<testcase classname=\"$it\" name=\"runs\"/>" }}</testsuite>"

    private fun suiteResult(): TestSuiteResult = (tempDir / "results/test-result.pb").inputStream().use(TestSuiteResult::parseFrom)

    private fun deviceInfo(): AndroidTestDeviceInfo =
        (tempDir / "results/device-info.pb").inputStream().use(AndroidTestDeviceInfo::parseFrom)

    private fun testReport(): Element {
        val file = tempDir / "results/TEST-mediumPhone.xml"
        assertThat(file).exists()
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile()).documentElement
    }

    private fun TestResult.artifactLabels(): List<String> = outputArtifactList.map { it.label.label }

    private fun TestResult.logcatPath(): String? = outputArtifactList.singleOrNull { it.label.label == "logcat" }?.sourcePath?.path

    private fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

    private fun Element.attributes(vararg names: String): List<String> = names.map { getAttribute(it) }

    private fun Element.properties(): List<Pair<String, String>> = child("properties").childElements("property").map {
        it.getAttribute("name") to it.getAttribute("value")
    }

    private fun Element.testCase(name: String): Element = childElements("testcase").single { it.getAttribute("name") == name }

    private fun Element.child(tagName: String): Element = childElements(tagName).single()

    private fun Element.childElements(tagName: String): List<Element> =
        List(childNodes.length) { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tagName }

    private companion object {
        private const val FIXTURE_PACKAGE = "io.technoirlab.device.run.fixture"
        private val ASSUMPTION_TRACE =
            """
            org.junit.AssumptionViolatedException: Exercises assumption failure reporting
            	at org.junit.Assume.assumeTrue(Assume.java:68)
            	at io.technoirlab.device.run.fixture.OutcomesTest.is skipped by assumption(OutcomesTest.kt:24)
            """.trimIndent()
        private val FAILURE_TRACE =
            """
            java.lang.AssertionError: Fails until a retry runs only failed tests: []
            	at org.junit.Assert.fail(Assert.java:89)
            	at org.junit.Assert.assertTrue(Assert.java:42)
            	at io.technoirlab.device.run.fixture.ProbeTest.fails unless reduced to failed tests(ProbeTest.kt:30)
            """.trimIndent()
        private const val CRASH_TRACE =
            "java.lang.IllegalStateException: Instrumentation crashed\n\tat a.FirstTest.crashes(FirstTest.kt:12)"
    }
}
