package io.technoirlab.device.run.gradle.testrun

import com.android.build.gradle.internal.test.report.CompositeTestResults
import com.android.build.gradle.internal.test.report.ReportType
import com.android.build.gradle.internal.test.report.TestReport
import io.technoirlab.device.run.core.RecordingDeviceRunner
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import io.technoirlab.device.run.core.files.PROBE_PUSHED_FILE
import io.technoirlab.device.run.core.files.PushManifestSerializer
import io.technoirlab.device.run.core.testrun.RunOptions
import io.technoirlab.device.run.core.testrun.appTestRunRequest
import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.job.TestReductionMode
import io.technoirlab.device.run.core.testrun.results.CapturedSession
import io.technoirlab.device.run.gradle.RecordedProblem
import io.technoirlab.device.run.gradle.RecordingProblems
import io.technoirlab.device.run.gradle.api.CapturePolicy
import io.technoirlab.device.run.gradle.api.DeviceOrientation
import io.technoirlab.device.run.gradle.api.RetryScope
import io.technoirlab.device.run.gradle.api.ShardingStrategy
import io.technoirlab.device.run.gradle.registerStubDeviceRunService
import io.technoirlab.gradle.asPath
import io.technoirlab.gradle.test.kit.createRootProject
import io.technoirlab.gradle.test.kit.subProject
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.newInstance
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import io.technoirlab.device.run.core.device.DeviceOrientation as CoreDeviceOrientation

class DeviceRunTestRunTaskActionTest {
    private val problems = RecordingProblems()
    private val action = object : DeviceRunTestRunTaskAction() {
        override val problems = this@DeviceRunTestRunTaskActionTest.problems
    }
    private lateinit var project: Project
    private lateinit var runner: RecordingDeviceRunner
    private lateinit var input: DeviceRunTestRunInput
    private lateinit var testApk: File
    private lateinit var testedApk: File
    private lateinit var apkFinder: RecordingTestedApkFinder
    private lateinit var testData: StubStaticTestData

    @BeforeEach
    fun setUp() {
        createRootProject("device-run-fixture") {
            this@DeviceRunTestRunTaskActionTest.project = subProject("app") {
                apply(plugin = "com.android.application")
            }
        }
        project.projectDir.toPath().createDirectories()
        val service = project.gradle.registerStubDeviceRunService()
        runner = service.get().runner as RecordingDeviceRunner
        testApk = project.file("app-free-debug-androidTest.apk").apply { writeText("test") }
        testedApk = project.file("app-free-debug.apk").apply { writeText("tested") }
        apkFinder = RecordingTestedApkFinder(listOf(testedApk))
        testData = StubStaticTestData(testApk, flavorName = "free", testedApkFinder = apkFinder)

        val manifest = project.layout.buildDirectory.file("device-run/push-manifest.json").get()
        PushManifestSerializer().write(listOf(PROBE_PUSHED_FILE), manifest.asPath().createParentDirectories())
        input = project.objects.newInstance<DeviceRunTestRunInput>().apply {
            locale.set("fr-CA")
            orientation.set(DeviceOrientation.LANDSCAPE)
            buildName.set("fixture")
            options.bucket.set("example-bucket")
            options.shardingStrategy.set(ShardingStrategy.UNIFORM)
            options.shardCount.set(4)
            options.retryScope.set(RetryScope.FAILED_TEST_CASES)
            options.pathsToPull.add("/sdcard/test-output")
            options.logcat.set(CapturePolicy.ALWAYS)
            options.video.set(CapturePolicy.ON_FAILURE)
            pushManifest.set(manifest)
        }
    }

    @Test
    fun `runs the tests of a device with the inputs of its test task`() {
        val parameters = parameters(testData.copy(instrumentationRunnerArguments = mapOf("size" to "large")))
        runner.testsPass = false

        val passed = action.runTests(parameters)

        val resultsDirectory = parameters.testRunData.outputDirectory.asPath()
        assertThat(passed).isFalse()
        assertThat(runner.testRuns).containsExactly(
            appTestRunRequest(testApk.toPath(), listOf(testedApk.toPath()), resultsDirectory).copy(
                options = RunOptions(
                    bucket = "example-bucket",
                    job = RunOptions.Job(
                        sharding = RunOptions.Sharding.Uniform(shardCount = 4),
                        retries = RunOptions.Retries(testReductionMode = TestReductionMode.REDUCE_TO_FAILED_TEST_CASES),
                        pathsToPull = listOf("/sdcard/test-output"),
                        capture = RunOptions.Capture(ArtifactCapture.ALWAYS, ArtifactCapture.ON_FAILURE),
                    ),
                ),
                device = DeviceOptions("mediumPhone", "mediumphone-arm-35", "fr-CA", CoreDeviceOrientation.LANDSCAPE),
                instrumentationRunner = "instrumentation.class",
                instrumentationRunnerArguments = mapOf("size" to "large"),
            ),
        )
        assertThat(apkFinder.configurations).singleElement().satisfies(
            { configuration ->
                assertThat(configuration.abis).containsExactly("arm64-v8a")
                assertThat(configuration.density).isEqualTo(420)
                assertThat(configuration.apiLevel).isEqualTo(35)
                assertThat(configuration.language).isEqualTo("fr")
                assertThat(configuration.region).isEqualTo("CA")
            },
        )
    }

    @Test
    fun `writes results that the AGP test report counts under the device of the test task`() {
        runner.capturedSession = CapturedSession("test-failure")
        val parameters = parameters(testData)

        val passed = action.runTests(parameters)

        val report = agpTestReport(listOf(parameters))
        assertThat(passed).isFalse()
        assertThat(listOf(report.testCount, report.failureCount, report.skipCount)).containsExactly(7, 1, 2)
        assertThat(probeTestReport())
            .content()
            .containsIgnoringNewLines("<th>Test</th><th>mediumPhone</th>")
            .containsIgnoringNewLines("""<td>fails unless reduced to failed tests</td><td class="failures">failed""")
    }

    @Test
    fun `writes tests that ended with an error as failures that the AGP test report counts`() {
        runner.capturedSession = CapturedSession("test-failure", jobReport = CRASHED_JOB_REPORT)
        val parameters = parameters(testData)

        action.runTests(parameters)

        val report = agpTestReport(listOf(parameters))
        assertThat(listOf(report.testCount, report.failureCount, report.skipCount)).containsExactly(2, 1, 0)
    }

    @Test
    fun `writes results that the AGP test report groups by project and tested variant`() {
        runner.capturedSession = CapturedSession("test-failure")
        val flavorParameters = listOf("free", "paid").map { flavor -> parameters(testData.copy(flavorName = flavor)) }

        for (parameters in flavorParameters) {
            action.runTests(parameters)
        }

        val report = agpTestReport(flavorParameters)
        assertThat(report.testCount).isEqualTo(14)
        assertThat(probeTestReport())
            .content()
            .containsSubsequence("Variants", ":app:freeDebug", ":app:paidDebug")
    }

    @Test
    fun `names the timing record of smart sharding after the build, project, variant, and catalog device by default`() {
        input.options.shardingStrategy.set(ShardingStrategy.SMART)
        input.options.shardTargetDuration.set(Duration.ofMinutes(5))

        action.runTests(parameters(testData))

        val defaultName = defaultTimingRecordName("fixture", ":app", "freeDebug", "mediumphone-arm-35")
        assertThat(runner.testRuns.map { it.options.job.sharding })
            .containsExactly(RunOptions.Sharding.Smart(Duration.ofMinutes(5), defaultName))
    }

    @Test
    fun `keeps the timing record name of smart sharding that the build sets`() {
        input.options.shardingStrategy.set(ShardingStrategy.SMART)
        input.options.shardTargetDuration.set(Duration.ofMinutes(5))
        input.options.timingRecordName.set("nightly")

        action.runTests(parameters(testData))

        assertThat(runner.testRuns.map { it.options.job.sharding })
            .containsExactly(RunOptions.Sharding.Smart(Duration.ofMinutes(5), "nightly"))
    }

    @Test
    fun `runs library tests without a tested application`() {
        val libraryTestData = StubStaticTestData(
            testApk = testApk,
            testedApplicationId = null,
            isLibrary = true,
            flavorName = "free",
            testedApkFinder = apkFinder,
        )

        action.runTests(parameters(libraryTestData))

        assertThat(runner.testRuns).singleElement().satisfies({ request -> assertThat(request.testedApks).isEmpty() })
        assertThat(apkFinder.configurations).isEmpty()
    }

    @Test
    fun `reports the AGP test options that Device Run devices do not apply as warnings`() {
        val parameters = parameters(testData.copy(animationsDisabled = true))

        action.runTests(parameters)

        assertThat(problems.reported).containsExactly(
            RecordedProblem(
                id = "device-run:unsupported-test-option",
                contextualLabel = "Animations are not disabled for the tests of :app",
            ),
        )
        assertThat(runner.testRuns).hasSize(1)
    }

    @Test
    fun `rejects a catalog device below the minimum SDK of the tests with a problem before selecting APKs`() {
        val parameters = parameters(testData.copy(minSdkVersion = StubAndroidVersion(apiLevel = 36)))

        assertThatThrownBy { action.runTests(parameters) }
            .isInstanceOf(InvalidUserDataException::class.java)
            .hasMessage("Device mediumPhone cannot run the tests of :app.")
        assertThat(problems.thrown).containsExactly(
            RecordedProblem(
                id = "device-run:api-level-below-min-sdk",
                contextualLabel = "Catalog device mediumphone-arm-35 runs API level 35, but the tests require at least API level 36",
                solutions = listOf(
                    "Set deviceRun.devices[\"mediumPhone\"].deviceId to a catalog device that runs API level 36 or higher.",
                ),
            ),
        )
        assertThat(apkFinder.configurations).isEmpty()
        assertThat(runner.testRuns).isEmpty()
    }

    @Test
    fun `fails with a problem when AGP selects no APK of the tested application for the catalog device`() {
        val parameters = parameters(testData.copy(testedApkFinder = RecordingTestedApkFinder(emptyList())))

        assertThatThrownBy { action.runTests(parameters) }
            .isInstanceOf(InvalidUserDataException::class.java)
            .hasMessage("Device mediumPhone cannot install the tested application of :app.")
        assertThat(problems.thrown).containsExactly(
            RecordedProblem(
                id = "device-run:no-tested-apk",
                contextualLabel = "AGP selected no APK of the tested application for catalog device mediumphone-arm-35, " +
                    "which supports ABIs arm64-v8a at API level 35",
                solutions = listOf(
                    "Build the tested application for an ABI of catalog device mediumphone-arm-35, or set " +
                        "deviceRun.devices[\"mediumPhone\"].deviceId to a catalog device that supports an ABI of the application.",
                ),
            ),
        )
        assertThat(runner.testRuns).isEmpty()
    }

    private fun parameters(testData: StubStaticTestData): StubDeviceTestRunParameters {
        val setupResults = managedDeviceSetupResults(project, DEVICE_NAME, MEDIUM_PHONE_SNAPSHOT)
        val testRunData = managedDeviceTestRunData(project, DEVICE_NAME, testData)
        return StubDeviceTestRunParameters(input, project.objects.directoryProperty().value(setupResults), testRunData)
    }

    private fun reportDirectory(): Directory = project.layout.buildDirectory.dir("reports/androidTests/managedDevice/mediumPhone").get()

    /**
     * Generates the AGP test report of the results that the test tasks with [parameters] wrote, in the form of AGP's managed device
     * test reports.
     */
    @Suppress("UnstableApiUsage")
    private fun agpTestReport(parameters: List<StubDeviceTestRunParameters>): CompositeTestResults =
        TestReport(ReportType.SINGLE_FLAVOR, parameters.map { it.testRunData.outputDirectory.asFile }, reportDirectory().asFile)
            .generateReport()

    private fun probeTestReport(): Path = reportDirectory().file("$FIXTURE_PACKAGE.ProbeTest.html").asPath()

    private companion object {
        private const val DEVICE_NAME = "mediumPhone"
        private const val FIXTURE_PACKAGE = "io.technoirlab.device.run.fixture"
        private const val CRASHED_JOB_REPORT =
            """<testsuite><testcase classname="com.example.LoginTest" name="logsIn"/>""" +
                """<testcase classname="com.example.LoginTest" name="crashes">""" +
                """<error>java.lang.IllegalStateException: Instrumentation crashed</error></testcase></testsuite>"""
    }
}
