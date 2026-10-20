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
import io.technoirlab.device.run.core.client.enqueueFixture
import io.technoirlab.device.run.core.client.parseDeviceRunFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments.arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.w3c.dom.Document
import org.w3c.dom.Element
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
    fun `recovered flaky tests keep their first failure in the job report`() {
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

    private fun Document.testCaseNames(): List<String> = testCases().map { "${it.getAttribute("classname")}#${it.getAttribute("name")}" }

    private companion object {
        private const val PARENT = "projects/example-project/locations/global"

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
        )
    }
}
