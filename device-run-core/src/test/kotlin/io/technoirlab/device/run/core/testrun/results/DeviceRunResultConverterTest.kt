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
import io.technoirlab.device.run.core.client.deviceRunFixtures
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
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
import org.w3c.dom.Element
import java.nio.file.Path
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.inputStream
import kotlin.io.path.writeText

class DeviceRunResultConverterTest {
    private val logs = recordLogs()
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
    fun `reports a test that ended with an error as a failure that AGP counts`() {
        val artifacts = fakeArtifact(
            "mediumPhone/junit.xml",
            "<testsuite>" +
                """<testcase classname="a.FirstTest" name="crashes"><error>$CRASH_TRACE</error></testcase>""" +
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
    fun `counts a flaky test as passed while keeping the raw job report`() {
        val artifacts = mirror(
            "flaky-retry-passed",
            "mediumPhone/junit.xml",
            "mediumPhone/execution-000/test_result.xml",
            "mediumPhone/execution-000-rerun-000/test_result.xml",
        ) + fakeArtifact("mediumPhone/execution-000/logcat.txt") + fakeArtifact("mediumPhone/execution-000-rerun-000/logcat.txt")

        val passed = converter.convert(session("flaky-retry-passed"), artifacts, context())

        val results = suiteResult()
        val flaky = results.testResultList.single { it.testCase.testMethod == "fails unless reduced to failed tests" }
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.testResultList).hasSize(7)
        assertThat(flaky.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(flaky.hasError()).isFalse()
        assertThat(flaky.artifactLabels()).containsExactly("device-info")
        assertThat(results.testResultList.filter { it != flaky }).allSatisfy { result ->
            assertThat(result.outputArtifactList.map { it.sourcePath.path }).contains(
                (tempDir / "mirror/mediumPhone/execution-000/logcat.txt").toString(),
            )
        }
        val testReport = testReport()
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
        assertThat(tempDir / "mirror/mediumPhone/junit.xml").content().contains("flaky=\"true\">\n    <failure>")
        assertThat(logs.entries).contains(
            Level.WARN to "1 test of mediumPhone is flaky, failing in some attempts and passing in others: " +
                "io.technoirlab.device.run.fixture.ProbeTest#fails unless reduced to failed tests (1 failed attempt).",
        )
    }

    @Test
    fun `writes a flaky failure for each failed attempt of a flaky test and a rerun failure for each later failure of a failed test`() {
        converter.convert(session("retry-outcomes"), mirrorOutputs("retry-outcomes"), context(jobDirectory = "smallPhone"))

        val testReport = testReport()
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
        val artifacts = fakeArtifact("mediumPhone/junit.xml", "<testsuite>$flakyTests</testsuite>")

        converter.convert(session("flaky-retry-passed"), artifacts, context())

        assertThat(logs.entries.filter { it.first == Level.WARN }.map { it.second }).containsExactly(
            "12 tests of mediumPhone are flaky, failing in some attempts and passing in others: " +
                (1..10).joinToString { "a.FlakyTest#flaky$it (1 failed attempt)" } + ", and 2 more.",
        )
    }

    @Test
    fun `fails a passed session whose crash left tests that no attempt ran`() {
        val passed = converter.convert(session("crash-tests-lost"), mirrorOutputs("crash-tests-lost"), context(jobDirectory = "smallPhone"))

        val results = suiteResult()
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
                        "ran the rest, so up to 2 tests did not run.",
                ),
            )
    }

    @Test
    fun `passes a session whose crash in the last test started every planned test`() {
        val passed = converter.convert(session("crash-last-test"), mirrorOutputs("crash-last-test"), context(jobDirectory = "smallPhone"))

        val results = suiteResult()
        assertThat(passed).isTrue()
        assertThat(results.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(testReport().testCase("b_crashesUnlessReduced").getAttribute("flaky")).isEqualTo("true")
    }

    @Test
    fun `explains a failed session with the class failures that only the instrumentation summary lists`() {
        val passed = converter.convert(
            session("orchestrator-class-failure"),
            mirrorOutputs("orchestrator-class-failure"),
            context(jobDirectory = "smallPhone"),
        )

        val results = suiteResult()
        val explanation = "The instrumentation summary of execution-000 lists 2 failures that the test results omit: " +
            "(io.technoirlab.rollup.plan.FailBeforeClassTest) java.lang.IllegalStateException: Fails the class in @BeforeClass"
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
        val passed = converter.convert(session("retry-outcomes"), mirrorOutputs("retry-outcomes"), context(jobDirectory = "smallPhone"))

        val results = suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.hasPlatformError()).isFalse()
        assertThat(results.testResultList.filter { it.testStatus == TestStatus.FAILED }.map { it.testCase.testMethod })
            .containsExactly("failsOnFirstAttempt")
        assertThat(results.issueList).isEmpty()
    }

    @Test
    fun `passes a session that Device Run failed only because no attempt of its shard passed every test`() {
        val passed = converter.convert(
            session("shard-retry-failed"),
            mirrorOutputs("shard-retry-failed"),
            context(jobDirectory = "smallPhone"),
        )

        val results = suiteResult()
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
        val session = session("shard-retry-failed").setSessionReport(session("report-without-executions").sessionReport)

        val passed = converter.convert(session, mirrorOutputs("shard-retry-failed"), context(jobDirectory = "smallPhone"))

        assertThat(passed).isTrue()
        assertThat(suiteResult().issueList.map { it.name to it.message }).containsExactly(
            "SESSION_HAS_FAILED_JOBS" to
                "Device Run failed session $SHARD_RETRY_SESSION (SESSION_HAS_FAILED_JOBS) because no attempt of execution-000 passed " +
                "every test. Every test passed in some attempt, so the tests of mediumPhone pass.",
        )
    }

    @Test
    fun `keeps the failure of such a session when an execution ended with an error`() {
        val session = session("shard-retry-failed").withExecutionResult(
            execution = "execution-000-rerun-001",
            resultType = "ERROR",
            cause = IssueSummary().setType("INFRA").setReason("PLATFORM_INFRASTRUCTURE_ERROR"),
        )

        val passed = converter.convert(session, mirrorOutputs("shard-retry-failed"), context(jobDirectory = "smallPhone"))

        val results = suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.platformError.errorsList.map { it.summary.errorName to it.summary.errorMessage }).containsExactly(
            "PLATFORM_INFRASTRUCTURE_ERROR" to "Execution execution-000-rerun-001 of job smallPhone ended with ERROR.",
        )
        assertThat(results.issueList).isEmpty()
    }

    @Test
    fun `keeps the failure of such a session when Device Run blames something other than failed jobs`() {
        val session = session("shard-retry-failed").apply { sessionReport.result.cause.summary.reason = "SESSION_HAS_ERROR_JOBS" }

        val passed = converter.convert(session, mirrorOutputs("shard-retry-failed"), context(jobDirectory = "smallPhone"))

        assertThat(passed).isFalse()
        assertThat(unexplainedFailures()).containsExactly(
            "the cause is SESSION_HAS_ERROR_JOBS, not SESSION_HAS_FAILED_JOBS",
        )
    }

    @Test
    fun `keeps the failure of such a session when the report of a failed execution lists no failed test`() {
        val artifacts = mirrorOutputs(
            "shard-retry-failed",
            mapOf("smallPhone/execution-000/junit.xml" to "<testsuites>${testSuite("a.FirstTest")}</testsuites>"),
        )

        val passed = converter.convert(session("shard-retry-failed"), artifacts, context(jobDirectory = "smallPhone"))

        assertThat(passed).isFalse()
        assertThat(unexplainedFailures()).containsExactly("execution-000 failed, but its report lists no failed test")
    }

    @Test
    fun `keeps the failure of such a session when no execution failed`() {
        val session = session("shard-retry-failed").apply {
            for (execution in listOf("execution-000", "execution-000-rerun-000", "execution-000-rerun-001")) {
                withExecutionResult(execution, resultType = "PASSED")
            }
        }

        val passed = converter.convert(session, mirrorOutputs("shard-retry-failed"), context(jobDirectory = "smallPhone"))

        assertThat(passed).isFalse()
        assertThat(unexplainedFailures()).containsExactly("no execution failed")
    }

    @Test
    fun `keeps the failure of such a session when the final report omits an attempt without an instrumentation log`() {
        val session = session("shard-retry-failed").setSessionReport(session("report-without-executions").sessionReport)
        val artifacts = mirrorOutputs("shard-retry-failed", mapOf(RETRY_LOG to null))

        val passed = converter.convert(session, artifacts, context(jobDirectory = "smallPhone"))

        assertThat(passed).isFalse()
        assertThat(unexplainedFailures()).containsExactly(
            "the session report omits execution-000-rerun-001, whose outputs lack an instrumentation log that ended normally",
        )
    }

    @Test
    fun `keeps the failure of such a session when the final report omits an attempt whose instrumentation log ended abnormally`() {
        val session = session("shard-retry-failed").setSessionReport(session("report-without-executions").sessionReport)
        val crashedLog = deviceRunFixture("sessions/shard-retry-failed/outputs/$RETRY_LOG")
            .replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0")
        val artifacts = mirrorOutputs("shard-retry-failed", mapOf(RETRY_LOG to crashedLog))

        val passed = converter.convert(session, artifacts, context(jobDirectory = "smallPhone"))

        assertThat(passed).isFalse()
        assertThat(unexplainedFailures()).containsExactly(
            "the session report omits execution-000-rerun-001, whose outputs lack an instrumentation log that ended normally",
        )
    }

    @Test
    fun `keeps the failure of a session whose crash left tests that no attempt ran`() {
        val session = session("crash-tests-lost").apply {
            sessionReport.result = Result().setResultType("FAILED").setCause(
                ResultCause().setSummary(IssueSummary().setReason("SESSION_HAS_FAILED_JOBS")),
            )
        }

        val passed = converter.convert(session, mirrorOutputs("crash-tests-lost"), context(jobDirectory = "smallPhone"))

        val results = suiteResult()
        assertThat(passed).isFalse()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.platformError.errorsList.map { it.summary.errorName }).containsExactly("INCOMPLETE_TEST_RUN")
        assertThat(results.issueList).isEmpty()
    }

    @Test
    fun `explains a session without a job report by the missing report rather than its instrumentation logs`() {
        val artifacts = mirrorOutputs("crash-tests-lost").filter { it.relativePath != "smallPhone/junit.xml" }

        converter.convert(session("crash-tests-lost"), artifacts, context(jobDirectory = "smallPhone"))

        assertThat(suiteResult().platformError.errorsList.map { it.summary.errorName }).containsExactly("MISSING_JOB_REPORT")
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

    /**
     * Mirrors every output of [scenario] that the fixtures keep, with the contents of [replacements] instead, or without the
     * outputs that it maps to null.
     */
    private fun mirrorOutputs(scenario: String, replacements: Map<String, String?> = emptyMap()): List<MirroredArtifact> =
        (deviceRunFixtures("sessions/$scenario/outputs") + replacements).flatMap { (relativePath, content) ->
            content?.let { fakeArtifact(relativePath, it) }.orEmpty()
        }

    private fun Session.withExecutionResult(execution: String, resultType: String, cause: IssueSummary? = null): Session = apply {
        sessionReport.jobReports.single().executionReports.single { it.displayName == execution }
            .setResult(Result().setResultType(resultType).setCause(cause?.let { ResultCause().setSummary(it) }))
    }

    /**
     * Returns why the failure of the converted session is unexplained, as its platform errors state.
     */
    private fun unexplainedFailures(): List<String> {
        val results = suiteResult()
        assertThat(results.testStatus).isEqualTo(TestStatus.FAILED)
        assertThat(results.issueList).isEmpty()
        return results.platformError.errorsList.map { it.summary }.map { summary ->
            assertThat(summary.errorName).isEqualTo("UNEXPLAINED_SESSION_FAILURE")
            assertThat(summary.errorClassification).isEqualTo("REPORTING")
            summary.errorMessage
                .removePrefix("Device Run failed the session although its job report lists no failed test: ")
                .removeSuffix(".")
        }
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

    private fun Element.childElements(tagName: String): List<Element> = childElements().filter { it.tagName == tagName }

    private fun Element.childElements(): List<Element> = List(childNodes.length) { childNodes.item(it) }.filterIsInstance<Element>()

    private fun Element.childTags(): List<String> = childElements().map { it.tagName }

    private companion object {
        private const val SHARD_RETRY_SESSION = "session-75dc46f0-4f32-4106-9af2-55c7539451dd"
        private const val RETRY_LOG = "smallPhone/execution-000-rerun-001/instrument.log"
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
