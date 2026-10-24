package io.technoirlab.device.run.core.testrun

import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus
import io.technoirlab.device.run.core.DeviceRunException
import io.technoirlab.device.run.core.VirtualClock
import io.technoirlab.device.run.core.client.DeviceRunLocation
import io.technoirlab.device.run.core.client.FakeCloudTransport
import io.technoirlab.device.run.core.client.StubCloudConnector
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import io.technoirlab.device.run.core.files.PROBE_PUSHED_FILE
import io.technoirlab.device.run.core.recordLogs
import io.technoirlab.device.run.core.testDeviceRunner
import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.results.CapturedScenario
import io.technoirlab.device.run.core.testrun.results.WrittenResults
import io.technoirlab.device.run.core.testrun.results.buildSessionReport
import io.technoirlab.device.run.core.testrun.results.properties
import io.technoirlab.device.run.core.testrun.session.SessionMetadataSerializer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.AutoClose
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.event.Level
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.div
import kotlin.io.path.writeText

class DeviceRunTestRunnerTest {
    private val cloud = FakeCloudTransport().apply {
        sessionOutputs = mapOf(JOB_REPORT_PATH to JOB_REPORT, EXECUTION_REPORT_PATH to EXECUTION_REPORT)
    }
    private val clock = VirtualClock()
    private val logs = recordLogs()
    private val location = DeviceRunLocation(StubCloudConnector.QUOTA_PROJECT_ID)
    private val executor = Executors.newFixedThreadPool(2)

    @AutoClose
    private val runner = testDeviceRunner(cloud, clock)

    @TempDir
    private lateinit var tempDir: Path

    private val writtenResults by lazy { WrittenResults(tempDir / RESULTS_PATH, DEVICE_NAME) }

    // Device Run failed the session although every test passed in some attempt, and its final report omits the executions.
    private val flakyShardScenario by lazy {
        CapturedScenario("shard-retry-failed").withJob(DEVICE_NAME).withSessionReportOf("report-without-executions")
    }

    @AfterEach
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `submits a session of the uploaded packages, the pushed files, and the runner arguments of the request`() {
        val request = request()

        val passed = runner.runTests(request)

        val config = cloud.sessions.single().sessionConfig
        val job = config.jobConfigs.single()
        val instrumentation = job.action.androidInstrumentationTest
        val deviceConfig = job.allocationConfig.deviceConfigs.single()
        val targetApp = deviceConfig.actions.mapNotNull { it.androidInstallPackages }.single().targetApp
        val pushedFiles = deviceConfig.actions.mapNotNull { it.androidPushFiles }.single().fileConfigs
        assertThat(passed).isTrue()
        assertThat(cloud.objectContent(instrumentation.testInstallable.files.single().gcsInputFile.path)).isEqualTo("test")
        assertThat(targetApp.files.map { cloud.objectContent(it.gcsInputFile.path) }).containsExactly("tested")
        assertThat(pushedFiles).flatExtracting({ it.sourceFile.gcsInputFile.path }).containsExactly(PROBE_PUSHED_FILE.uri.toString())
        assertThat(deviceConfig.requirement.deviceId).isEqualTo(MEDIUM_PHONE_SNAPSHOT.deviceId)
        assertThat(instrumentation.testRunnerClass).isEqualTo("androidx.test.runner.AndroidJUnitRunner")
        assertThat(instrumentation.testTargets).containsExactly("class com.example.LoginTest")
        assertThat(instrumentation.additionalTestOptions).containsExactlyEntriesOf(mapOf("clearPackageData" to "true"))
        assertThat(job.labels).containsExactlyInAnyOrderEntriesOf(request.labels)
        assertThat(config.displayName).isEqualTo("app freeDebug mediumPhone")
        assertThat(config.outputDirectoryConfig.gcsOutputDirectory.path).isEqualTo("gs://$BUCKET/automation/sessions")
    }

    @Test
    fun `writes the results of the finished session to the results directory of the request`() {
        val request = request()

        val passed = runner.runTests(request)

        val metadata = SessionMetadataSerializer().read(request.resultsDirectory / "device-run-session.json")
        val suiteResult = writtenResults.suiteResult()
        val deviceInfo = writtenResults.deviceInfo()
        assertThat(passed).isTrue()
        assertThat(metadata.sessionName).isEqualTo(cloud.sessions.single().name)
        assertThat(writtenResults.mirroredFile(JOB_REPORT_PATH)).content().isEqualTo(JOB_REPORT)
        assertThat(suiteResult.testStatus).isEqualTo(TestStatus.PASSED)
        assertThat(suiteResult.testResultList.map { it.testCase.testMethod }).containsExactly("logsIn")
        assertThat(deviceInfo.gradleDslDeviceName).isEqualTo("mediumPhone")
        assertThat(deviceInfo.model).isEqualTo(MEDIUM_PHONE_SNAPSHOT.displayName)
        assertThat(writtenResults.testReport().properties().toMap()).isEqualTo(request.reportProperties)
    }

    @Test
    fun `runs the tests with an orchestrator version that Device Run provides`() {
        cloud.orchestratorVersions = listOf("1.4.1", "1.6.1")

        val passed = runner.runTests(request(RunOptions(BUCKET, job = RunOptions.Job(orchestratorVersion = "1.6.1"))))

        val instrumentation = cloud.sessions.single().sessionConfig.jobConfigs.single().action.androidInstrumentationTest
        assertThat(passed).isTrue()
        assertThat(instrumentation.orchestratorVersion).isEqualTo("1.6.1")
    }

    @Test
    fun `checks the orchestrator version before uploading the packages`() {
        cloud.orchestratorVersions = listOf("1.4.1", "1.6.1")
        val request = request(RunOptions(BUCKET, job = RunOptions.Job(orchestratorVersion = "1.9.0")))

        assertThatThrownBy { runner.runTests(request) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessageStartingWith("androidx.test:orchestrator:1.9.0 from androidTestUtil is not available in Device Run.")
        assertThat(cloud.requests).containsExactly(
            FakeCloudTransport.Request("GET", FakeCloudTransport.DEVICE_RUN_HOST, "/v1alpha/${location.name}/softwareVersions"),
        )
    }

    @Test
    fun `reports APKs that cannot be uploaded and submits no session`() {
        cloud.deniedPrefixes += "automation/inputs/"

        assertThatThrownBy { runner.runTests(request()) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage("Unable to upload the APKs of :app freeDebug to bucket $BUCKET: 403 PERMISSION_DENIED: Permission denied")
            .hasCauseInstanceOf(GoogleJsonResponseException::class.java)
        assertThat(cloud.sessions).isEmpty()
    }

    @Test
    fun `downloads the outputs that the session report references once Device Run has uploaded them`() {
        cloud.outputUploadListings = mapOf(EXECUTION_REPORT_PATH to 2)
        cloud.sessionReport = { sessionDirectory ->
            buildSessionReport {
                result("PASSED")
                job(DEVICE_NAME) {
                    outputFile("$sessionDirectory/$JOB_REPORT_PATH")
                    execution("execution-000") { outputFile("$sessionDirectory/$EXECUTION_REPORT_PATH") }
                }
            }
        }

        val passed = runner.runTests(request())

        // The report lists every execution, so the first listing of every file that it references is final.
        assertThat(passed).isTrue()
        assertThat(writtenResults.mirroredFile(JOB_REPORT_PATH)).content().isEqualTo(JOB_REPORT)
        assertThat(writtenResults.mirroredFile(EXECUTION_REPORT_PATH)).content().isEqualTo(EXECUTION_REPORT)
        assertThat(clock.sleeps).containsExactly(SETTLE_INTERVAL, SETTLE_INTERVAL)
    }

    @Test
    fun `reports an interrupted wait for the outputs as an interruption`() {
        clock.interruptNextSleep()
        val request = request()

        val failure = catchThrowable { runner.runTests(request) }
        val interrupted = Thread.interrupted()

        assertThat(failure).isInstanceOf(InterruptedException::class.java)
        assertThat(interrupted).isFalse()
        assertThat(cloud.requests).containsOnlyOnce(FakeCloudTransport.Request("GET", FakeCloudTransport.STORAGE_HOST, OBJECT_LISTING_PATH))
        assertThat(writtenResults.mirrorDirectory).doesNotExist()
    }

    @ParameterizedTest
    @ValueSource(strings = ["PASSED", "FAILED"])
    fun `waits for the outputs of the executions of a session whose tests ran when its report omits them`(resultType: String) {
        cloud.outputUploadListings = mapOf(EXECUTION_REPORT_PATH to 2)
        cloud.sessionReport = { buildSessionReport { result(resultType) } }

        val passed = runner.runTests(request())

        // Matching listings of the job report alone would settle the outputs without the execution report.
        assertThat(passed).isEqualTo(resultType == "PASSED")
        assertThat(writtenResults.mirroredFile(JOB_REPORT_PATH)).content().isEqualTo(JOB_REPORT)
        assertThat(writtenResults.mirroredFile(EXECUTION_REPORT_PATH)).content().isEqualTo(EXECUTION_REPORT)
    }

    @Test
    fun `fails a session whose tests ran without a job report`() {
        cloud.sessionOutputs = mapOf(EXECUTION_REPORT_PATH to EXECUTION_REPORT)
        cloud.sessionReport = { buildSessionReport { result("FAILED") } }

        val passed = runner.runTests(request())

        val suiteResult = writtenResults.suiteResult()
        assertThat(passed).isFalse()
        assertThat(suiteResult.platformError.errorsList.map { it.summary.errorName }).containsExactly("MISSING_JOB_REPORT")
    }

    @Test
    fun `passes a session that failed only because no attempt passed every test, once the retries that its report omits arrive`() {
        flakyShardScenario.uploadTo(cloud)
        cloud.outputUploadListings = flakyShardScenario.outputs.keys.filter { "-rerun-" in it }.associateWith { 1 }

        val passed = runner.runTests(request())

        // A single early listing would miss the retries, which confirm that every failed attempt failed tests.
        assertThat(passed).isTrue()
        assertThat(writtenResults.mirroredFile("mediumPhone/execution-000-rerun-001/instrument.log")).exists()
    }

    @Test
    fun `mirrors the outputs of a session that ended with an error without waiting for a job report`() {
        cloud.sessionOutputs = mapOf(DEVICE_LOG_PATH to "logcat")
        cloud.sessionReport = { buildSessionReport { result("ERROR") } }

        val passed = runner.runTests(request())

        assertThat(passed).isFalse()
        assertThat(writtenResults.mirroredFile(DEVICE_LOG_PATH)).exists()
        assertThat(logs.entries.map { it.first }).doesNotContain(Level.WARN)
    }

    @Test
    fun `ignores the session report outputs that do not name an object`() {
        cloud.sessionReport = { sessionDirectory ->
            buildSessionReport {
                result("PASSED")
                job(DEVICE_NAME) { outputFile("$sessionDirectory/mediumPhone/") }
            }
        }

        val passed = runner.runTests(request())

        assertThat(passed).isTrue()
    }

    @Test
    fun `reports session outputs that cannot be downloaded`() {
        cloud.deniedPrefixes += "automation/sessions/"

        val failure = catchThrowable { runner.runTests(request()) }

        assertThat(failure)
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                "Unable to download the outputs of Device Run session ${cloud.sessions.single().name} from bucket $BUCKET: " +
                    "403 PERMISSION_DENIED: Permission denied",
            )
            .hasCauseInstanceOf(GoogleJsonResponseException::class.java)
    }

    @ParameterizedTest
    @CsvSource("ON_FAILURE, FAILED", "ALWAYS, PASSED")
    fun `downloads device logs that the logcat policy keeps for the verdict on the tests`(logcat: ArtifactCapture, resultType: String) {
        cloud.sessionOutputs = mapOf(JOB_REPORT_PATH to JOB_REPORT, DEVICE_LOG_PATH to "logcat")
        cloud.sessionReport = { buildSessionReport { result(resultType) } }

        val passed = runner.runTests(request(logcat))

        assertThat(passed).isEqualTo(resultType == "PASSED")
        assertThat(writtenResults.mirroredFile(JOB_REPORT_PATH)).exists()
        assertThat(writtenResults.mirroredFile(DEVICE_LOG_PATH)).exists()
    }

    @ParameterizedTest
    @CsvSource("ON_FAILURE, PASSED", "DISABLED, FAILED")
    fun `skips device logs that the logcat policy discards for the verdict on the tests`(logcat: ArtifactCapture, resultType: String) {
        cloud.sessionOutputs = mapOf(JOB_REPORT_PATH to JOB_REPORT, DEVICE_LOG_PATH to "logcat")
        cloud.sessionReport = { buildSessionReport { result(resultType) } }

        val passed = runner.runTests(request(logcat))

        assertThat(passed).isEqualTo(resultType == "PASSED")
        assertThat(writtenResults.mirroredFile(JOB_REPORT_PATH)).exists()
        assertThat(writtenResults.mirroredFile(DEVICE_LOG_PATH)).doesNotExist()
    }

    @Test
    fun `downloads the device logs of tests that fail when Device Run passed the session`() {
        cloud.sessionOutputs = mapOf(JOB_REPORT_PATH to FAILING_JOB_REPORT, DEVICE_LOG_PATH to "logcat")
        cloud.sessionReport = { buildSessionReport { result("PASSED") } }

        val passed = runner.runTests(request(ArtifactCapture.ON_FAILURE))

        assertThat(passed).isFalse()
        assertThat(writtenResults.mirroredFile(DEVICE_LOG_PATH)).exists()
    }

    @Test
    fun `skips the device logs of tests that pass when Device Run failed the session`() {
        flakyShardScenario.uploadTo(cloud)

        val passed = runner.runTests(request(ArtifactCapture.ON_FAILURE))

        assertThat(passed).isTrue()
        assertThat(writtenResults.mirroredFile("mediumPhone/execution-000/instrument.log")).exists()
        assertThat(writtenResults.mirroredFile(DEVICE_LOG_PATH)).doesNotExist()
    }

    @Test
    fun `submits sessions that share a smart sharding timing record without waiting for each other`() {
        // The barrier opens only while both sessions are being submitted at the same time.
        val submissions = CyclicBarrier(2)
        cloud.onRequest = { received ->
            if (received.method == "POST" && received.path.endsWith("/sessions")) {
                submissions.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }
        }
        val requests = listOf("first", "second").map { request(SMART_SHARDING).copy(resultsDirectory = tempDir / it) }

        val testRuns = requests.map { request -> executor.submit<Boolean> { runner.runTests(request) } }
        val passed = testRuns.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

        assertThat(passed).containsExactly(true, true)
        assertThat(cloud.sessions).hasSize(2)
    }

    private fun request(options: RunOptions = RunOptions(BUCKET)): TestRunRequest = appTestRunRequest(
        testApk = (tempDir / "app-free-debug-androidTest.apk").apply { writeText("test") },
        testedApks = listOf((tempDir / "app-free-debug.apk").apply { writeText("tested") }),
        resultsDirectory = tempDir / RESULTS_PATH,
    ).copy(
        options = options,
        instrumentationRunnerArguments = mapOf("class" to "com.example.LoginTest", "clearPackageData" to "true"),
    )

    private fun request(logcat: ArtifactCapture): TestRunRequest =
        request(RunOptions(BUCKET, job = RunOptions.Job(capture = RunOptions.Capture(logcat = logcat))))

    private companion object {
        private const val BUCKET = "example-bucket"
        private const val DEVICE_NAME = "mediumPhone"
        private const val RESULTS_PATH = "outputs/androidTest-results/managedDevice/freeDebug/mediumPhone"
        private const val OBJECT_LISTING_PATH = "/storage/v1/b/$BUCKET/o"
        private const val TIMEOUT_SECONDS = 10L
        private val SETTLE_INTERVAL = Duration.ofSeconds(5)
        private const val JOB_REPORT_PATH = "mediumPhone/junit.xml"
        private const val EXECUTION_REPORT_PATH = "mediumPhone/execution-000/test_result.xml"
        private const val DEVICE_LOG_PATH = "mediumPhone/execution-000/logcat.txt"
        private const val JOB_REPORT = """<testsuite><testcase classname="com.example.LoginTest" name="logsIn"/></testsuite>"""
        private const val FAILING_JOB_REPORT =
            """<testsuite><testcase classname="com.example.LoginTest" name="logsIn"><failure>Failed</failure></testcase></testsuite>"""
        private const val EXECUTION_REPORT = "<testsuites>$JOB_REPORT</testsuites>"
        private val SMART_SHARDING =
            RunOptions(BUCKET, job = RunOptions.Job(sharding = RunOptions.Sharding.Smart(Duration.ofMinutes(5), "login")))
    }
}
