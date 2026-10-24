package io.technoirlab.device.run.core.testrun.results

import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import io.technoirlab.device.run.core.attributes
import io.technoirlab.device.run.core.child
import io.technoirlab.device.run.core.childElements
import io.technoirlab.device.run.core.childTags
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import io.technoirlab.device.run.core.recordLogs
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.event.Level
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.div

class DeviceRunResultConverterTest {
    private val logs = recordLogs()
    private val converter = DeviceRunResultConverter()
    private val passedSession = buildSession { result("PASSED") }

    @TempDir
    private lateinit var tempDir: Path

    private val writtenResults by lazy { WrittenResults(tempDir / "results", DEVICE_NAME) }

    @Test
    fun `reports passing, ignored, assumption-skipped, and parameterized tests of a library`() {
        val scenario = CapturedScenario("library-passed")

        val passed = convert(scenario)

        val results = writtenResults.suiteResult()
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
        val testReport = writtenResults.testReport()
        assertThat(testReport.attributes("tests", "failures", "errors", "skipped", "time")).containsExactly("5", "0", "0", "2", "1.032")
        assertThat(testReport.properties()).containsExactly("device" to "mediumPhone", "flavor" to "", "project" to ":android-library")
        val deviceInfo = writtenResults.deviceInfo()
        assertThat(deviceInfo.apiLevel).isEqualTo("35")
        assertThat(deviceInfo.abisList).containsExactly("arm64-v8a")
        assertThat(deviceInfo.model).isEqualTo("Medium Phone, 6.4in/16cm (Arm)")
    }

    @Test
    fun `reports a test failure with a single copy of its trace and no platform error`() {
        val scenario = CapturedScenario("test-failure").withSessionReport {
            jobReports.single().warnings = listOf(reportWarning(reason = "SLOW_DEVICE", message = "The device was slow to boot."))
        }

        val passed = convert(scenario)

        val results = writtenResults.suiteResult()
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
        val failureElement = writtenResults.testReport().testCase("fails unless reduced to failed tests").child("failure")
        assertThat(failureElement.getAttribute("type")).isEqualTo("java.lang.AssertionError")
        assertThat(failureElement.textContent).isEqualTo(FAILURE_TRACE)
    }

    @Test
    fun `reports a test that ended with an error as a failure that AGP counts`() {
        val scenario = CapturedScenario("test-failure").withOutput(
            "mediumPhone/junit.xml",
            "<testsuite>" +
                """<testcase classname="a.FirstTest" name="crashes"><error>$CRASH_TRACE</error></testcase>""" +
                """<testcase classname="a.FirstTest" name="runs"/>""" +
                "</testsuite>",
        )

        convert(scenario)

        val errored = writtenResults.suiteResult().testResultList.single { it.testCase.testMethod == "crashes" }
        assertThat(errored.testStatus).isEqualTo(TestStatus.ERROR)
        assertThat(errored.error.errorType).isEqualTo("java.lang.IllegalStateException")
        assertThat(errored.error.errorMessage).isEqualTo("Instrumentation crashed")
        assertThat(errored.error.stackTrace).isEqualTo(CRASH_TRACE)
        val testReport = writtenResults.testReport()
        val reportedTest = testReport.testCase("crashes")
        assertThat(testReport.attributes("tests", "failures", "errors")).containsExactly("2", "1", "0")
        assertThat(reportedTest.hasAttribute("flaky")).isFalse()
        assertThat(reportedTest.childElements("error")).isEmpty()
        assertThat(reportedTest.child("failure").attributes("type", "message"))
            .containsExactly("java.lang.IllegalStateException", "Instrumentation crashed")
        assertThat(reportedTest.child("failure").textContent).isEqualTo(CRASH_TRACE)
    }

    @Test
    fun `counts a flaky test as passed while keeping the raw job report`() {
        val scenario = CapturedScenario("flaky-retry-passed")

        val passed = convert(scenario)

        val results = writtenResults.suiteResult()
        val flaky = results.testResultList.single { it.testCase.testMethod == "fails unless reduced to failed tests" }
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.testResultList).hasSize(7)
        assertThat(flaky.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(flaky.hasError()).isFalse()
        assertThat(flaky.artifactLabels()).containsExactly("device-info")
        assertThat(results.testResultList.filter { it != flaky }).allSatisfy { result ->
            assertThat(result.outputArtifactList.map { it.sourcePath.path }).contains(
                writtenResults.mirroredFile("mediumPhone/execution-000/logcat.txt").toString(),
            )
        }
        val testReport = writtenResults.testReport()
        val flakyTest = testReport.testCase("fails unless reduced to failed tests")
        assertThat(testReport.attributes("tests", "failures", "skipped", "flakes")).containsExactly("7", "0", "2", "1")
        assertThat(flakyTest.getAttribute("flaky")).isEqualTo("true")
        assertThat(flakyTest.getElementsByTagName("failure").length).isZero()
        assertThat(flakyTest.child("flakyFailure").attributes("type", "message")).containsExactly(
            "java.lang.AssertionError",
            "Fails until a retry runs only failed tests: [$FLAKY_RETRY_ARGUMENTS]",
        )
        assertThat(flakyTest.child("flakyFailure").child("stackTrace").textContent).isEqualTo(
            FAILURE_TRACE.replace("tests: []", "tests: [$FLAKY_RETRY_ARGUMENTS]"),
        )
        assertThat(writtenResults.mirroredFile("mediumPhone/junit.xml")).content().contains("flaky=\"true\">\n    <failure>")
        assertThat(logs.entries).contains(
            Level.WARN to "1 test of mediumPhone is flaky, failing in some attempts and passing in others: " +
                "$FIXTURE_PACKAGE.ProbeTest#fails unless reduced to failed tests (1 failed attempt).",
        )
    }

    @Test
    fun `writes a flaky failure for each failed attempt of a flaky test and a rerun failure for each later failure of a failed test`() {
        convert(CapturedScenario("retry-outcomes"))

        val testReport = writtenResults.testReport()
        assertThat(testReport.attributes("tests", "failures", "errors", "skipped", "flakes")).containsExactly("7", "1", "0", "0", "4")
        assertThat(testReport.childElements("testcase").associate { it.getAttribute("name") to it.childTags() }).containsExactlyEntriesOf(
            mapOf(
                "passes" to emptyList(),
                "coin0" to listOf("flakyFailure", "flakyFailure"),
                "coin1" to listOf("flakyFailure"),
                "coin2" to listOf("flakyFailure", "flakyFailure"),
                "coin3" to listOf("flakyFailure"),
                "failsOnFirstAttempt" to listOf("failure", "rerunFailure", "rerunFailure"),
                "failsOnSecondAttempt" to emptyList(),
            ),
        )
        assertThat(testReport.getElementsByTagName("system-out").length).isZero()
        val failedTest = testReport.testCase("failsOnFirstAttempt")
        val attemptStarts = listOf(failedTest.child("failure").textContent) +
            failedTest.childElements("rerunFailure").map { it.child("stackTrace").textContent }
        assertThat(attemptStarts.map { START.find(it)?.value }).containsExactly("start=231078", "start=231025", "start=246578")
        assertThat(failedTest.childElements("rerunFailure")).allSatisfy { rerun ->
            assertThat(rerun.attributes("type")).containsExactly("java.lang.AssertionError")
            assertThat(rerun.getAttribute("message")).startsWith("Fails on the first attempt: test=failsOnFirstAttempt attempt=0")
        }
    }

    @Test
    fun `names at most ten flaky tests in one warning`() {
        val flakyTests = (1..12).joinToString("") { index ->
            """<testcase classname="a.FlakyTest" name="flaky$index" flaky="true"><failure>$CRASH_TRACE</failure></testcase>"""
        }
        val scenario = CapturedScenario("flaky-retry-passed").withOutput("mediumPhone/junit.xml", "<testsuite>$flakyTests</testsuite>")

        convert(scenario)

        assertThat(logs.entries.filter { it.first == Level.WARN }.map { it.second }).containsExactly(
            "12 tests of mediumPhone are flaky, failing in some attempts and passing in others: " +
                (1..10).joinToString { "a.FlakyTest#flaky$it (1 failed attempt)" } + ", and 2 more.",
        )
    }

    @Test
    fun `fails a passed session whose crash left tests that no attempt ran`() {
        val passed = convert(CapturedScenario("crash-tests-lost"))

        val results = writtenResults.suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.ERROR)
        assertThat(results.testResultList.map { it.testCase.testMethod to it.testStatus }).containsExactly(
            "a_passes" to TestStatus.PASSED,
            "b_crashesOnFirstAttempt" to TestStatus.PASSED,
        )
        assertThat(results.platformError.errorsList.map { it.summary })
            .extracting({ it.errorName }, { it.errorClassification }, { it.errorMessage })
            .containsExactly(
                tuple(
                    "INCOMPLETE_TEST_RUN",
                    "INSTRUMENTATION",
                    "The instrumentation of execution-000 ended with \"Process crashed.\" after starting 2 of its 4 tests, and no retry " +
                        "ran the rest, so 2 tests did not run.",
                ),
            )
    }

    @Test
    fun `warns instead of failing when class events leave it uncertain whether a crash left tests unrun`() {
        val passed = convert(CapturedScenario("class-level-crash-last"))

        val results = writtenResults.suiteResult()
        val warning = "The instrumentation of execution-000 ended with \"Process crashed.\" after starting 3 entries for its 4 tests, " +
            "and no retry ran the rest. Entries of class events, such as an assumption in @BeforeClass, stand for several tests, so " +
            "up to 1 test may not have run."
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(results.issueList.map { it.name to it.message }).containsExactly("POSSIBLY_INCOMPLETE_TEST_RUN" to warning)
        assertThat(logs.entries).contains(Level.WARN to warning)
    }

    @Test
    fun `passes a session whose flaky tests had several failures in one attempt, which the summary lists separately`() {
        val passed = convert(CapturedScenario("multiple-failures"))

        val results = writtenResults.suiteResult()
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(results.issueList).isEmpty()
    }

    @Test
    fun `passes a session whose crash in the last test started every planned test`() {
        val passed = convert(CapturedScenario("crash-last-test"))

        val results = writtenResults.suiteResult()
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(writtenResults.testReport().testCase("b_crashesUnlessReduced").getAttribute("flaky")).isEqualTo("true")
    }

    @Test
    fun `explains a failed session with the class failures that only the instrumentation summary lists`() {
        val passed = convert(CapturedScenario("orchestrator-class-failure"))

        val results = writtenResults.suiteResult()
        val explanation = "The instrumentation summary of execution-000 lists 2 failures that the test results omit: " +
            "$FIXTURE_PACKAGE.FailBeforeClassTest java.lang.IllegalStateException: Fails the class in @BeforeClass"
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.testResultList.map { it.testStatus }).containsExactly(TestStatus.PASSED)
        assertThat(results.platformError.errorsList.map { it.summary })
            .extracting({ it.errorName }, { it.errorClassification }, { it.errorMessage })
            .containsExactly(tuple("UNREPORTED_TEST_FAILURES", "INSTRUMENTATION", explanation))
        assertThat(logs.entries).contains(Level.ERROR to "Device Run INSTRUMENTATION UNREPORTED_TEST_FAILURES: $explanation")
    }

    @Test
    fun `finds no tests that did not run or failures that the reports omit when every retry completes`() {
        val passed = convert(CapturedScenario("retry-outcomes"))

        val results = writtenResults.suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(results.testResultList.filter { it.testStatus == TestStatus.FAILED }.map { it.testCase.testMethod })
            .containsExactly("failsOnFirstAttempt")
        assertThat(results.issueList).isEmpty()
    }

    @Test
    fun `passes a session that Device Run failed only because no attempt of its shard passed every test`() {
        val passed = convert(CapturedScenario("shard-retry-failed"))

        val results = writtenResults.suiteResult()
        val warning = "Device Run failed session $SHARD_RETRY_SESSION (JOB_HAS_FAILED_TESTS) because no attempt of execution-000 passed " +
            "every test. Every test passed in some attempt, so the tests of mediumPhone pass."
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(results.testResultList).hasSize(16).allSatisfy { assertThat(it.testStatus).isEqualTo(TestStatus.PASSED) }
        assertThat(results.issueList.map { it.name to it.message }).containsExactly("JOB_HAS_FAILED_TESTS" to warning)
        assertThat(logs.entries).contains(
            Level.INFO to
                "Device Run session projects/example-project/locations/global/sessions/$SHARD_RETRY_SESSION finished with FAILED, and " +
                "the tests passed: 16 tests, 16 passed (7 flaky), 0 failed, 0 errors, 0 skipped.",
            Level.WARN to warning,
        )
    }

    @Test
    fun `passes such a session by the outputs of its attempts when the final report omits them`() {
        val scenario = CapturedScenario("shard-retry-failed").withSessionReportOf("report-without-executions")

        val passed = convert(scenario)

        assertThat(passed).isTrue()
        assertThat(writtenResults.suiteResult().issueList.map { it.name to it.message }).containsExactly(
            "SESSION_HAS_FAILED_JOBS" to
                "Device Run failed session $SHARD_RETRY_SESSION (SESSION_HAS_FAILED_JOBS) because no attempt of execution-000 passed " +
                "every test. Every test passed in some attempt, so the tests of mediumPhone pass.",
        )
    }

    @Test
    fun `keeps the failure of such a session and explains it when the report of a failed attempt lists no failed test`() {
        val scenario = CapturedScenario("shard-retry-failed")
            .withOutput("smallPhone/execution-000/junit.xml", "<testsuites>${testSuite("a.FirstTest")}</testsuites>")

        val passed = convert(scenario)

        val results = writtenResults.suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.issueList).isEmpty()
        assertThat(results.platformError.errorsList.map { it.summary })
            .extracting({ it.errorName }, { it.errorClassification }, { it.errorMessage })
            .containsExactly(
                tuple(
                    "UNEXPLAINED_SESSION_FAILURE",
                    "REPORTING",
                    "Device Run failed the session although its job report lists no failed test: execution-000 failed, but its report " +
                        "lists no failed test.",
                ),
            )
    }

    @Test
    fun `keeps the failure of such a session and explains it when an execution ended with an error`() {
        val scenario = CapturedScenario("shard-retry-failed").withSessionReport {
            jobReports.single().executionReports.single { it.displayName == "execution-000-rerun-001" }.result =
                reportResult("ERROR", type = "INFRA", reason = "PLATFORM_INFRASTRUCTURE_ERROR")
        }

        val passed = convert(scenario)

        val results = writtenResults.suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.issueList).isEmpty()
        assertThat(results.platformError.errorsList.map { it.summary })
            .extracting({ it.errorName }, { it.errorClassification }, { it.errorMessage })
            .containsExactly(
                tuple("PLATFORM_INFRASTRUCTURE_ERROR", "INFRA", "Execution execution-000-rerun-001 of job smallPhone ended with ERROR."),
            )
    }

    @Test
    fun `explains a session without a job report by the missing report rather than its instrumentation logs`() {
        val scenario = CapturedScenario("crash-tests-lost").withoutOutput("smallPhone/junit.xml")

        convert(scenario)

        assertThat(writtenResults.suiteResult().platformError.errorsList.map { it.summary.errorName }).containsExactly("MISSING_JOB_REPORT")
    }

    @Test
    fun `reports infrastructure errors from execution causes without a job report`() {
        val passed = convert(CapturedScenario("infra-error"))

        val results = writtenResults.suiteResult()
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
            "deviceRun.pulledFile" to "text/plain",
            "logcat" to "text/plain",
            "deviceRun.video" to "video/webm",
        )
        assertThat(writtenResults.testReportFile).doesNotExist()
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
            CapturedScenario("cancelled").session
        } else {
            buildSession { result(resultType, message = "The instrumentation timed out.") }
        }

        val passed = convert(session, artifacts = emptyList(), context())

        val results = writtenResults.suiteResult()
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
        val artifacts = jobReport?.let { mirror("library/junit.xml" to it) }.orEmpty()

        val passed = convert(CapturedScenario("library-passed").session, artifacts, context(jobDirectory = "library"))

        val results = writtenResults.suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.ERROR)
        assertThat(results.platformError.errorsList.map { it.summary.errorName to it.summary.errorClassification })
            .containsExactly(reason to "REPORTING")
    }

    @ParameterizedTest
    @ValueSource(strings = ["failure", "error"])
    fun `fails a passing session whose job report lists a failing test`(outcome: String) {
        val artifacts = mirror(
            "library/junit.xml" to
                """<testsuite><testcase classname="a.FirstTest" name="runs"><$outcome>$CRASH_TRACE</$outcome></testcase></testsuite>""",
        )

        val passed = convert(CapturedScenario("library-passed").session, artifacts, context(jobDirectory = "library"))

        val results = writtenResults.suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.hasPlatformError()).isFalse()
    }

    @Test
    fun `attaches to each test of a sharded job the log of its shard`() {
        val artifacts = mirror(
            "mediumPhone/junit.xml" to testSuite("a.FirstTest", "b.SecondTest"),
            "mediumPhone/execution-000/test_result.xml" to testSuite("a.FirstTest"),
            "mediumPhone/execution-000/logcat.txt" to "logcat",
            "mediumPhone/execution-001/artifacts/sdcard/logcat.txt" to "pulled",
            "mediumPhone/execution-001/test_result.xml" to testSuite("b.SecondTest"),
            "mediumPhone/execution-001/logcat.txt" to "logcat",
        )

        convert(passedSession, artifacts, context())

        val results = writtenResults.suiteResult()
        assertThat(results.testResultList.associate { it.testCase.testClass to it.logcatPath() }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "FirstTest" to writtenResults.mirroredFile("mediumPhone/execution-000/logcat.txt").toString(),
                "SecondTest" to writtenResults.mirroredFile("mediumPhone/execution-001/logcat.txt").toString(),
            ),
        )
        assertThat(results.outputArtifactList.filter { it.label.label == "logcat" }).hasSize(2)
    }

    @Test
    fun `labels the job report apart from the execution reports of physical devices, which have the same name`() {
        val artifacts = mirror(
            "mediumPhone/junit.xml" to testSuite("a.FirstTest"),
            "mediumPhone/execution-000/junit.xml" to "<testsuites>${testSuite("a.FirstTest")}</testsuites>",
            "mediumPhone/execution-000/logcat.txt" to "logcat",
        )

        convert(passedSession, artifacts, context())

        assertThat(writtenResults.suiteResult().outputArtifactList.map { it.label.label })
            .containsExactly("device-info", "deviceRun.testResult", "logcat", "deviceRun.junit")
    }

    /**
     * Writes the results of the captured session of [scenario] with its mirrored outputs as the runner does, and returns whether its
     * tests passed.
     */
    private fun convert(scenario: CapturedScenario): Boolean =
        convert(scenario.session, scenario.mirrorTo(writtenResults.mirrorDirectory), context(jobDirectory = scenario.job))

    /**
     * Writes the results of [session] as the runner does, and returns whether its tests passed.
     */
    private fun convert(session: Session, artifacts: List<MirroredArtifact>, context: ResultContext): Boolean {
        val verdict = converter.evaluate(session, artifacts, context)
        converter.write(session, artifacts, context, verdict)
        return verdict.passed
    }

    private fun context(jobDirectory: String = "mediumPhone"): ResultContext = ResultContext(
        jobDirectory = jobDirectory,
        resultsDirectory = (tempDir / "results").createDirectories(),
        deviceName = DEVICE_NAME,
        device = MEDIUM_PHONE_SNAPSHOT,
        reportProperties = mapOf(
            "device" to "mediumPhone",
            "flavor" to "",
            "project" to if (jobDirectory == "library") ":android-library" else ":android-application",
        ),
    )

    private fun mirror(vararg outputs: Pair<String, String>): List<MirroredArtifact> =
        mirrorOutputs(writtenResults.mirrorDirectory, mapOf(*outputs))

    private fun testSuite(vararg classNames: String): String =
        "<testsuite>${classNames.joinToString("") { "<testcase classname=\"$it\" name=\"runs\"/>" }}</testsuite>"

    private companion object {
        private const val DEVICE_NAME = "mediumPhone"
        private const val SHARD_RETRY_SESSION = "session-75dc46f0-4f32-4106-9af2-55c7539451dd"
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
        private val START = Regex("start=\\d+")
        private const val FLAKY_RETRY_ARGUMENTS = "class=$FIXTURE_PACKAGE.OutcomesTest,$FIXTURE_PACKAGE.ParameterizedTest," +
            "$FIXTURE_PACKAGE.ProbeTest, probeOption=probe-value"
    }
}
