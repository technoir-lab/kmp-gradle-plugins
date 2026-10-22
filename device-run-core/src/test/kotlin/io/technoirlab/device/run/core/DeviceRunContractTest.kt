package io.technoirlab.device.run.core

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.json.GenericJson
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.devicerun.v1alpha.DeviceRun
import com.google.api.services.devicerun.v1alpha.model.CancelSessionResponse
import com.google.api.services.devicerun.v1alpha.model.CatalogDevice
import com.google.api.services.devicerun.v1alpha.model.CatalogListSoftwareVersionsResponse
import com.google.api.services.devicerun.v1alpha.model.GoogleLongrunningOperation
import com.google.api.services.devicerun.v1alpha.model.OperationMetadata
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.storage.model.Objects
import io.technoirlab.device.run.core.client.RecordingHttpTransport
import io.technoirlab.device.run.core.client.deviceRunFixture
import io.technoirlab.device.run.core.client.deviceRunFixtures
import io.technoirlab.device.run.core.client.enqueueFixture
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

class DeviceRunContractTest {
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private val transport = RecordingHttpTransport()
    private val deviceRun = DeviceRun.Builder(transport, jsonFactory, null).setApplicationName("test").build()

    @ParameterizedTest
    @MethodSource("responses")
    fun `captured responses map to typed client fields`(path: String, type: Class<out GenericJson>) {
        val response = parseDeviceRunFixture(path, type)

        assertThat(response.unknownFieldPaths()).isEmpty()
    }

    @Test
    fun `completed operations finish sessions whose verdict comes from the session report`() {
        val operation = parseDeviceRunFixture<GoogleLongrunningOperation>("sessions/library-passed/operation-done.json")
        val session = parseDeviceRunFixture<Session>("sessions/test-failure/session-full.json")

        val failedJob = session.sessionReport.jobReports.single()
        val failedExecution = failedJob.executionReports.single()
        assertThat(operation.done).isTrue()
        assertThat(operation.error).isNull()
        assertThat(operation.operationMetadata().statusMessage).isEqualTo("Session is finished")
        assertThat(session.sessionReport.status.statusType).isEqualTo("DONE")
        assertThat(session.sessionReport.result.resultType).isEqualTo("FAILED")
        assertThat(session.sessionReport.result.cause.summary.type).isNull()
        assertThat(failedExecution.result.cause.summary.type).isEqualTo("CUSTOMER")
        assertThat(failedExecution.result.cause.summary.reason).isEqualTo("TEST_CASE_FAILURE")
        assertThat(failedJob.outputFiles.single().gcsOutputFile.path)
            .isEqualTo("gs://example-bucket/automation/sessions/session-2d2e6c4c-4568-47b5-9c55-174fc66df39a/mediumPhone/junit.xml")
    }

    @Test
    fun `final reports leave absent collections null`() {
        val session = parseDeviceRunFixture<Session>("sessions/flaky-retry-passed/session-full.json")

        assertThat(session.sessionReport.result.resultType).isEqualTo("PASSED")
        assertThat(session.sessionReport.jobReports).isNull()
    }

    @Test
    fun `final reports can omit the executions of a job whose outputs exist`() {
        val session = parseDeviceRunFixture<Session>("sessions/report-without-executions/session-full.json")
        val listing = parseDeviceRunFixture<Objects>("sessions/report-without-executions/objects.json")

        val job = session.sessionReport.jobReports.single()
        assertThat(session.sessionReport.result.resultType).isEqualTo("FAILED")
        assertThat(session.sessionReport.result.cause.summary.reason).isEqualTo("SESSION_HAS_FAILED_JOBS")
        assertThat(job.result.resultType).isEqualTo("FAILED")
        assertThat(job.result.cause).isNull()
        assertThat(job.executionReports).isNull()
        assertThat(job.outputFiles).isNull()
        assertThat(listing.items.map { it.name.substringAfter("/smallPhone/") }).containsExactly(
            "execution-000-rerun-000/instrument.log",
            "execution-000-rerun-000/junit.xml",
            "execution-000-rerun-000/logcat.txt",
            "execution-000-rerun-001/instrument.log",
            "execution-000-rerun-001/junit.xml",
            "execution-000-rerun-001/logcat.txt",
            "execution-000/instrument.log",
            "execution-000/junit.xml",
            "execution-000/logcat.txt",
            "junit.xml",
        )
    }

    @Test
    fun `outputs are uploaded within seconds after the session ends, the job report first`() {
        val session = parseDeviceRunFixture<Session>("sessions/report-without-executions/session-full.json")
        val listing = parseDeviceRunFixture<Objects>("sessions/report-without-executions/objects.json")

        val sessionEnd = Instant.parse(session.sessionReport.endTime)
        val uploads = listing.items.sortedBy { it.timeCreated.value }
        val delays = uploads.map { Duration.between(sessionEnd, Instant.ofEpochMilli(it.timeCreated.value)) }
        assertThat(uploads.first().name).endsWith("/smallPhone/junit.xml")
        assertThat(delays).allSatisfy { delay -> assertThat(delay).isPositive().isLessThan(Duration.ofSeconds(5)) }
    }

    @Test
    fun `invalid arguments keep the violated field in the error message`() {
        transport.enqueueFixture("errors/create-invalid-argument.json", statusCode = 400)
        val create = deviceRun.projects().locations().sessions().create(PARENT, Session())

        assertThatThrownBy { create.execute() }
            .isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { exception ->
                assertThat(exception.statusCode).isEqualTo(400)
                assertThat(exception.details["status"]).isEqualTo("INVALID_ARGUMENT")
                assertThat(exception.details.message).isEqualTo(
                    "Violation in CreateSessionRequest.session.session_config.job_configs[0].settings.retry_settings." +
                        "flaky_test_retry_strategy.flaky_test_attempts: flaky_test_attempts must be between 1 and 5.",
                )
            }
    }

    @Test
    fun `disabled APIs report the service and activation details`() {
        transport.enqueueFixture("errors/service-disabled.json", statusCode = 403)
        val get = deviceRun.projects().locations().devices().get("$PARENT/devices/mediumphone-arm-35")

        assertThatThrownBy { get.execute() }
            .isInstanceOfSatisfying(GoogleJsonResponseException::class.java) { exception ->
                assertThat(exception.statusCode).isEqualTo(403)
                assertThat(exception.details["status"]).isEqualTo("PERMISSION_DENIED")
                assertThat(exception.details.details.first().reason).isEqualTo("SERVICE_DISABLED")
            }
    }

    @ParameterizedTest
    @CsvSource(
        "sessions/library-passed/outputs/library, 5",
        "sessions/test-failure/outputs/mediumPhone, 7",
        "sessions/flaky-retry-passed/outputs/mediumPhone, 7",
    )
    fun `job reports aggregate the tests of their executions under a different root`(jobDirectory: String, testCount: Int) {
        val aggregate = parseXml("$jobDirectory/junit.xml")
        val execution = parseXml("$jobDirectory/execution-000/test_result.xml")

        assertThat(aggregate.documentElement.tagName).isEqualTo("testsuite")
        assertThat(execution.documentElement.tagName).isEqualTo("testsuites")
        assertThat(aggregate.testCaseNames()).hasSize(testCount).containsExactlyElementsOf(execution.testCaseNames())
    }

    @Test
    fun `retries reduced to failed tests rerun only the failed test, which the job report marks flaky`() {
        val aggregate = parseXml("sessions/flaky-retry-passed/outputs/mediumPhone/junit.xml")
        val rerun = parseXml("sessions/flaky-retry-passed/outputs/mediumPhone/execution-000-rerun-000/test_result.xml")

        val flakyTest = aggregate.testCases().single { it.getAttribute("flaky") == "true" }
        assertThat(aggregate.documentElement.getAttribute("flakes")).isEqualTo("1")
        assertThat(aggregate.documentElement.getAttribute("failures")).isEqualTo("0")
        assertThat(flakyTest.getAttribute("name")).isEqualTo("fails unless reduced to failed tests")
        assertThat(flakyTest.getElementsByTagName("failure").length).isEqualTo(1)
        assertThat(
            rerun.testCaseNames(),
        ).containsExactly("io.technoirlab.device.run.fixture.ProbeTest#fails unless reduced to failed tests")
    }

    @ParameterizedTest
    @ValueSource(strings = ["shard-retry-failed", "retry-outcomes", "crash-tests-lost", "crash-last-test", "report-without-executions"])
    fun `job reports roll up the attempts of each test into one test case`(scenario: String) {
        val jobReport = parseXml("sessions/$scenario/outputs/smallPhone/junit.xml")
        val attempts = attemptReports(scenario)

        val runsByTest = attempts.values.flatMap { it.testCases() }.groupBy { it.testName() }
        assertThat(jobReport.testCases().map { it.rollup() }).containsExactlyInAnyOrderElementsOf(
            runsByTest.map { (name, runs) ->
                RolledUpTest(
                    name = name,
                    flaky = runs.any { it.failures().isNotEmpty() } && runs.any { it.failures().isEmpty() },
                    // Each failure repeats the trace of its attempt.
                    failures = runs.flatMap { it.failures() }.map { "$it\n\n$it" },
                    time = runs.maxOf { it.time() },
                )
            },
        )
        assertThat(jobReport.getElementsByTagName("error").length).isZero()
        assertThat(jobReport.documentElement.attributes("failures", "flakes", "errors")).containsExactly(
            runsByTest.values.count { runs -> runs.all { it.failures().isNotEmpty() } }.toString(),
            jobReport.testCases().count { it.getAttribute("flaky") == "true" }.toString(),
            "0",
        )
        assertThat(jobReport.documentElement.time()).isEqualTo(attempts.values.maxOf { it.testSuite().time() })
    }

    @Test
    fun `whole-shard retries fail the session when no attempt passes every test, although every test passed in some attempt`() {
        val session = parseDeviceRunFixture<Session>("sessions/shard-retry-failed/session-full.json")
        val jobReport = parseXml("sessions/shard-retry-failed/outputs/smallPhone/junit.xml")
        val attempts = attemptReports("shard-retry-failed")

        val job = session.sessionReport.jobReports.single()
        val retryStrategy = session.sessionConfig.jobConfigs.single().settings.retrySettings.flakyTestRetryStrategy
        assertThat(retryStrategy.testReductionMode).isEqualTo("NO_REDUCTION")
        assertThat(jobReport.documentElement.attributes("tests", "failures", "flakes")).containsExactly("16", "0", "7")
        assertThat(session.sessionReport.result.resultType).isEqualTo("FAILED")
        assertThat(session.sessionReport.result.cause.summary.reason).isEqualTo("SESSION_HAS_FAILED_JOBS")
        assertThat(job.result.resultType).isEqualTo("FAILED")
        assertThat(job.result.cause.summary.reason).isEqualTo("JOB_HAS_FAILED_TESTS")
        assertThat(job.executionReports.map { it.displayName }).containsExactlyInAnyOrderElementsOf(attempts.keys)
        assertThat(job.executionReports).allSatisfy { execution ->
            assertThat(execution.result.resultType).isEqualTo("FAILED")
            assertThat(execution.result.cause.summary.type).isEqualTo("CUSTOMER")
            assertThat(execution.result.cause.summary.reason).isEqualTo("TEST_CASE_FAILURE")
            assertThat(attempts.getValue(execution.displayName).testCases()).anySatisfy { assertThat(it.failures()).isNotEmpty() }
        }
    }

    @Test
    fun `a crash under failed-test retries ends the instrumentation early, and the retry runs only the crashed test`() {
        val session = parseDeviceRunFixture<Session>("sessions/crash-tests-lost/session-full.json")
        val crashed = instrumentLog("sessions/crash-tests-lost/outputs/smallPhone/execution-000/instrument.log")
        val retry = instrumentLog("sessions/crash-tests-lost/outputs/smallPhone/execution-000-rerun-000/instrument.log")
        val jobReport = parseXml("sessions/crash-tests-lost/outputs/smallPhone/junit.xml")

        assertThat(crashed.statusValues("numtests")).containsOnly("4")
        assertThat(crashed.statusValues("test").distinct()).containsExactly("a_passes", "b_crashesOnFirstAttempt")
        assertThat(crashed.count { it == "INSTRUMENTATION_STATUS_CODE: 1" }).isEqualTo(2)
        assertThat(crashed.takeLast(2)).containsExactly("INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "INSTRUMENTATION_CODE: 0")
        assertThat(retry.statusValues("numtests")).containsOnly("1")
        assertThat(retry.statusValues("test").distinct()).containsExactly("b_crashesOnFirstAttempt")
        assertThat(retry.last()).isEqualTo("INSTRUMENTATION_CODE: -1")
        assertThat(jobReport.testCaseNames()).containsExactly(
            "io.technoirlab.rollup.probe.CrashTest#a_passes",
            "io.technoirlab.rollup.probe.CrashTest#b_crashesOnFirstAttempt",
        )
        assertThat(session.sessionReport.result.resultType).isEqualTo("PASSED")
    }

    @Test
    fun `a crash in the last test starts every planned test`() {
        val crashed = instrumentLog("sessions/crash-last-test/outputs/smallPhone/execution-000/instrument.log")

        assertThat(crashed.statusValues("numtests")).containsOnly("2")
        assertThat(crashed.count { it == "INSTRUMENTATION_STATUS_CODE: 1" }).isEqualTo(2)
        assertThat(crashed.takeLast(2)).containsExactly("INSTRUMENTATION_RESULT: shortMsg=Process crashed.", "INSTRUMENTATION_CODE: 0")
    }

    @Test
    fun `class-level events start one entry for several tests of a run that ends normally`() {
        val log = instrumentLog("instrument-logs/class-level-events.log")

        assertThat(log.statusValues("numtests")).containsOnly("8")
        assertThat(log.count { it == "INSTRUMENTATION_STATUS_CODE: 1" }).isEqualTo(6)
        assertThat(log.statusValues("test").distinct()).containsExactly("null", "assumesInTest", "passes", "isIgnored")
        assertThat(log).containsSubsequence(
            "There was 1 failure:",
            "1) io.technoirlab.rollup.plan.FailBeforeClassTest",
            "java.lang.IllegalStateException: Fails the class in @BeforeClass",
            "FAILURES!!!",
            "INSTRUMENTATION_CODE: -1",
        )
    }

    @Test
    fun `instrumentation summaries of the orchestrator list class failures that the reports omit`() {
        val session = parseDeviceRunFixture<Session>("sessions/orchestrator-class-failure/session-full.json")
        val log = instrumentLog("sessions/orchestrator-class-failure/outputs/smallPhone/execution-000/instrument.log")
        val jobReport = parseXml("sessions/orchestrator-class-failure/outputs/smallPhone/junit.xml")
        val executionReport = parseXml("sessions/orchestrator-class-failure/outputs/smallPhone/execution-000/junit.xml")

        val execution = session.sessionReport.jobReports.single().executionReports.single()
        assertThat(session.sessionConfig.jobConfigs.single().action.androidInstrumentationTest.orchestratorVersion).isEqualTo("1.6.1")
        assertThat(log).containsSubsequence(
            "There were 2 failures:",
            "1) (io.technoirlab.rollup.plan.FailBeforeClassTest)",
            "java.lang.IllegalStateException: Fails the class in @BeforeClass",
            "2) (io.technoirlab.rollup.plan.FailBeforeClassTest)",
            "java.lang.IllegalStateException: Fails the class in @BeforeClass",
            "FAILURES!!!",
            "Tests found: 3, Tests run: 1,  Failures: 2",
            "INSTRUMENTATION_CODE: -1",
        )
        assertThat(log).doesNotContain("INSTRUMENTATION_STATUS_CODE: -2")
        assertThat(jobReport.testCaseNames()).containsExactly("io.technoirlab.rollup.plan.PassingTest#passes")
        assertThat(executionReport.testCaseNames()).containsExactly("io.technoirlab.rollup.plan.PassingTest#passes")
        assertThat(jobReport.getElementsByTagName("failure").length + executionReport.getElementsByTagName("failure").length).isZero()
        assertThat(execution.result.resultType).isEqualTo("FAILED")
        assertThat(execution.result.cause.summary.reason).isEqualTo("TEST_CASE_FAILURE")
        assertThat(session.sessionReport.result.resultType).isEqualTo("FAILED")
        assertThat(session.sessionReport.result.cause.summary.reason).isEqualTo("SESSION_HAS_FAILED_JOBS")
    }

    private fun GoogleLongrunningOperation.operationMetadata(): OperationMetadata =
        jsonFactory.fromString(jsonFactory.toString(metadata), OperationMetadata::class.java)

    private fun GenericJson.unknownFieldPaths(path: String = "$"): List<String> =
        unknownKeys.keys.filter { it != "@type" }.map { "$path.$it" } +
            entries.flatMap { (key, value) -> value.nestedUnknownFieldPaths("$path.$key") }

    private fun Any?.nestedUnknownFieldPaths(path: String): List<String> = when (this) {
        is GenericJson -> unknownFieldPaths(path)
        is List<*> -> flatMapIndexed { index, element -> element.nestedUnknownFieldPaths("$path[$index]") }
        else -> emptyList()
    }

    private fun parseXml(path: String): Document = DocumentBuilderFactory.newInstance()
        .newDocumentBuilder()
        .parse(deviceRunFixture(path).byteInputStream())

    private fun Document.testCases(): List<Element> = getElementsByTagName("testcase").let { nodes ->
        List(nodes.length) { index -> nodes.item(index) as Element }
    }

    private fun Document.testCaseNames(): List<String> = testCases().map { it.testName() }

    private fun Document.testSuite(): Element = documentElement.getElementsByTagName("testsuite").item(0) as Element

    /**
     * Returns the execution reports of the job of [scenario], by execution name in attempt order.
     */
    private fun attemptReports(scenario: String): Map<String, Document> = deviceRunFixtures("sessions/$scenario/outputs").keys
        .mapNotNull { EXECUTION_REPORT.matchEntire(it)?.groupValues?.get(1) }
        .sorted()
        .associateWith { parseXml("sessions/$scenario/outputs/smallPhone/$it/junit.xml") }

    private fun instrumentLog(path: String): List<String> = deviceRunFixture(path).trimEnd().lines()

    private fun List<String>.statusValues(key: String): List<String> =
        mapNotNull { line -> line.removePrefix("INSTRUMENTATION_STATUS: $key=").takeIf { it != line } }

    private fun Element.testName(): String = "${getAttribute("classname")}#${getAttribute("name")}"

    private fun Element.failures(): List<String> = getElementsByTagName("failure").let { nodes ->
        List(nodes.length) { index -> nodes.item(index).textContent.trim() }
    }

    private fun Element.rollup(): RolledUpTest = RolledUpTest(
        name = testName(),
        flaky = getAttribute("flaky") == "true",
        failures = failures(),
        time = time(),
    )

    /**
     * Returns the `time` attribute without trailing zeros, since reports write times with varying precision, such as 0.0 and 0.000.
     */
    private fun Element.time(): BigDecimal = getAttribute("time").toBigDecimal().stripTrailingZeros()

    private fun Element.attributes(vararg names: String): List<String> = names.map { getAttribute(it) }

    /**
     * The outcome of a test across its attempts.
     */
    private data class RolledUpTest(
        val name: String,
        val flaky: Boolean,
        val failures: List<String>,
        val time: BigDecimal,
    )

    private companion object {
        private const val PARENT = "projects/example-project/locations/global"
        private val EXECUTION_REPORT = Regex("smallPhone/([^/]+)/junit\\.xml")
        private val CAPTURED_ROLLUP_SESSIONS = listOf(
            "shard-retry-failed",
            "retry-outcomes",
            "crash-tests-lost",
            "crash-last-test",
            "orchestrator-class-failure",
            "report-without-executions",
        )

        @JvmStatic
        fun responses() = listOf(
            arguments("catalog/device-mediumphone-arm-35.json", CatalogDevice::class.java),
            arguments("catalog/orchestrator-versions-page-1.json", CatalogListSoftwareVersionsResponse::class.java),
            arguments("catalog/orchestrator-versions-page-2.json", CatalogListSoftwareVersionsResponse::class.java),
            arguments("sessions/library-passed/operation-created.json", GoogleLongrunningOperation::class.java),
            arguments("sessions/library-passed/operation-done.json", GoogleLongrunningOperation::class.java),
            arguments("sessions/library-passed/request.json", Session::class.java),
            arguments("sessions/library-passed/session-full.json", Session::class.java),
            arguments("sessions/test-failure/request.json", Session::class.java),
            arguments("sessions/test-failure/session-full.json", Session::class.java),
            arguments("sessions/infra-error/operation-created.json", GoogleLongrunningOperation::class.java),
            arguments("sessions/infra-error/operation-create-replayed.json", GoogleLongrunningOperation::class.java),
            arguments("sessions/infra-error/session-running-basic.json", Session::class.java),
            arguments("sessions/infra-error/session-full.json", Session::class.java),
            arguments("sessions/flaky-retry-passed/request.json", Session::class.java),
            arguments("sessions/flaky-retry-passed/operation-created.json", GoogleLongrunningOperation::class.java),
            arguments("sessions/flaky-retry-passed/operation-done.json", GoogleLongrunningOperation::class.java),
            arguments("sessions/flaky-retry-passed/session-running-basic.json", Session::class.java),
            arguments("sessions/flaky-retry-passed/session-full.json", Session::class.java),
            arguments("sessions/flaky-retry-passed/objects.json", Objects::class.java),
            arguments("sessions/cancelled/cancel-response.json", CancelSessionResponse::class.java),
            arguments("sessions/cancelled/session-full.json", Session::class.java),
        ) + CAPTURED_ROLLUP_SESSIONS.flatMap { scenario ->
            listOf(
                arguments("sessions/$scenario/request.json", Session::class.java),
                arguments("sessions/$scenario/session-full.json", Session::class.java),
            )
        } + arguments("sessions/report-without-executions/objects.json", Objects::class.java)
    }
}
