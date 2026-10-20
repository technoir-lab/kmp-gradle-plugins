package io.technoirlab.device.run.gradle

import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus.PASSED
import com.google.testing.platform.proto.api.core.TestStatusProto.TestStatus.SKIPPED
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import io.technoirlab.gradle.test.kit.GradleRunnerExtension
import io.technoirlab.gradle.test.kit.appendBuildScript
import io.technoirlab.gradle.test.kit.buildDir
import io.technoirlab.gradle.test.kit.buildScript
import io.technoirlab.gradle.test.kit.replaceText
import io.technoirlab.gradle.test.kit.settingsScript
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.tuple
import org.gradle.testkit.runner.TaskOutcome.FAILED
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.div
import kotlin.io.path.inputStream

class DeviceRunPluginFunctionalTest {
    @RegisterExtension
    private val gradleRunner = GradleRunnerExtension("device-run-fixture")

    @Test
    fun `project plugin applied without the settings plugin provides the build service to cloud tasks`() {
        gradleRunner.root.settingsScript.replaceText(
            "id(\"io.technoirlab.device-run.settings\")\n",
            "id(\"io.technoirlab.device-run\") apply false\n",
        )
        for (module in MODULES) {
            val androidPlugin = "id(\"com.android.${module.removePrefix("android-")}\")\n"
            gradleRunner.root.project(module).buildScript.replaceText(
                androidPlugin,
                "$androidPlugin    id(\"io.technoirlab.device-run\")\n",
            )
        }

        val result = gradleRunner.build(PREPARE_PUSHED_FILES_TASK_NAME) {
            arguments.add("--parallel")
        }

        assertThat(MODULES).allSatisfy { module ->
            assertThat(result.task(":$module:$PREPARE_PUSHED_FILES_TASK_NAME")?.outcome).isEqualTo(SUCCESS)
        }
    }

    @Test
    fun `dry run schedules generated inputs, pushed file preparation, and setup before cloud tests without credentials`() {
        gradleRunner.root.project("android-application").appendBuildScript(
            """
            abstract class GenerateProbe : DefaultTask() {
                @get:OutputFile
                abstract val outputFile: RegularFileProperty
            
                @TaskAction
                fun generate() {
                    outputFile.get().asFile.writeText("probe")
                }
            }
            
            val generateProbe = tasks.register<GenerateProbe>("generateProbe") {
                outputFile = layout.buildDirectory.file("probe/probe-input.txt")
            }
            
            deviceRun {
                files {
                    push.register("probe") {
                        localFile = generateProbe.flatMap { it.outputFile }
                        destinationPath = "/sdcard/probe-input.txt"
                    }
                }
            }
            """.trimIndent(),
        )

        val result = gradleRunner.build(TEST_TASK) {
            dryRun = true
            environmentVariables.putAll(System.getenv())
            environmentVariables["GOOGLE_APPLICATION_CREDENTIALS"] = gradleRunner.root.dir / "missing-credentials.json"
        }

        for (module in MODULES) {
            assertThat(result.output).containsSubsequence(
                ":$module:deviceRunPreparePushedFiles SKIPPED",
                ":$module:smallPhoneDebugAndroidTest SKIPPED",
            )
            assertThat(result.output).containsSubsequence(":$module:smallPhoneSetup SKIPPED", ":$module:smallPhoneDebugAndroidTest SKIPPED")
        }
        assertThat(result.output).containsSubsequence(
            ":android-application:generateProbe SKIPPED",
            ":android-application:deviceRunPreparePushedFiles SKIPPED",
        )
    }

    @Test
    fun `invalid options fail cloud tasks with a problem for each`() {
        gradleRunner.root.project("android-application").appendBuildScript(
            """
            deviceRun {
                devices.named("smallPhone") {
                    deviceId = "small phone"
                }
                files.push.register("input") {
                    destinationPath = "sdcard/input.txt"
                }
            }
            """.trimIndent(),
        )

        val result = gradleRunner.build(SETUP_TASK, PREPARE_PUSHED_FILES_TASK, expectFailure = true) {
            arguments.add("--continue")
        }

        val outputLines = result.output.lines().map { it.trim() }
        assertThat(result.task(SETUP_TASK)?.outcome).isEqualTo(FAILED)
        assertThat(result.task(PREPARE_PUSHED_FILES_TASK)?.outcome).isEqualTo(FAILED)
        assertThat(outputLines).containsSequence(
            "Invalid Device Run option",
            "deviceRun.devices[\"smallPhone\"].deviceId 'small phone' is not a catalog device ID.",
        )
        assertThat(outputLines).containsSequence(
            "Invalid Device Run option",
            "deviceRun.files.push[\"input\"] sets neither gcsUri nor localFile; set exactly one source.",
            "Invalid Device Run option",
            "deviceRun.files.push[\"input\"].destinationPath 'sdcard/input.txt' must be an absolute device path.",
        )
    }

    @Test
    fun `cloud tests with orchestrator execution reuse the configuration cache`() {
        gradleRunner.root.project("android-application").appendBuildScript(ORCHESTRATOR_EXECUTION)

        val results = List(2) {
            gradleRunner.build(":android-application:$TEST_TASK") {
                dryRun = true
            }
        }

        assertThat(results.first().output).contains("Configuration cache entry stored.")
        assertThat(results.last().output).contains("Reusing configuration cache.")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FUNCTIONAL_TEST_ALLOW_DEVICE_RUN", matches = "true")
    fun `cloud tests run with ADC and report passing ignored assumed and parameterized outcomes`() {
        val result = gradleRunner.build(TEST_TASK) {
            gradleProperties["deviceRunBucket"] = deviceRunBucket()
        }

        for (module in MODULES) {
            assertThat(result.task(":$module:$TEST_TASK")?.outcome).isEqualTo(SUCCESS)

            val reportFile = resultsDirectory(module) / "TEST-smallPhone.xml"
            val report = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(reportFile.toFile())
            val results = testSuiteResult(module)

            assertThat(listOf("tests", "failures", "skipped").map(report.documentElement::getAttribute)).containsExactly("5", "0", "2")

            assertThat(results.testStatus).isEqualTo(PASSED)
            assertThat(results.platformError.errorsList).isEmpty()
            assertThat(results.testResultList)
                .extracting({ it.testCase.testClass }, { it.testCase.testMethod }, { it.testStatus })
                .containsExactlyInAnyOrder(
                    tuple("OutcomesTest", "passes after one second", PASSED),
                    tuple("OutcomesTest", "is ignored", SKIPPED),
                    tuple("OutcomesTest", "is skipped by assumption", SKIPPED),
                    tuple("ParameterizedTest", "squares number[0]", PASSED),
                    tuple("ParameterizedTest", "squares number[1]", PASSED),
                )
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FUNCTIONAL_TEST_ALLOW_DEVICE_RUN", matches = "true")
    fun `cloud tests run with Android Test Orchestrator and the test services of Device Run`() {
        gradleRunner.root.project("android-application").appendBuildScript(
            listOf(ORCHESTRATOR_EXECUTION, TEST_SERVICES_PROBE, ORCHESTRATOR_PROBE).joinToString(separator = "\n\n"),
        )

        val result = gradleRunner.build(":android-application:$TEST_TASK") {
            gradleProperties["deviceRunBucket"] = deviceRunBucket()
        }

        val results = testSuiteResult("android-application")
        assertThat(result.output).doesNotContain(UNSUPPORTED_OPTION_PROBLEM)
        assertThat(results.testStatus).isEqualTo(PASSED)
        assertThat(results.testResultList)
            .filteredOn { it.testCase.testClass.endsWith("ProbeTest") }
            .extracting({ it.testCase.testClass }, { it.testCase.testMethod }, { it.testStatus })
            .containsExactlyInAnyOrder(
                tuple("OrchestratorProbeTest", "first test runs alone with cleared data", PASSED),
                tuple("OrchestratorProbeTest", "second test runs alone with cleared data", PASSED),
                tuple("OrchestratorProbeTest", "orchestrator has the version of androidTestUtil", PASSED),
                tuple("TestServicesProbeTest", "writes a test output file through test services", PASSED),
            )
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FUNCTIONAL_TEST_ALLOW_DEVICE_RUN", matches = "true")
    fun `cloud tests without the orchestrator run with the test services of Device Run`() {
        gradleRunner.root.project("android-application").appendBuildScript(TEST_SERVICES_PROBE)

        val result = gradleRunner.build(":android-application:$TEST_TASK") {
            gradleProperties["deviceRunBucket"] = deviceRunBucket()
        }

        val results = testSuiteResult("android-application")
        assertThat(result.output).doesNotContain(UNSUPPORTED_OPTION_PROBLEM)
        assertThat(results.testStatus).isEqualTo(PASSED)
        assertThat(results.testResultList)
            .filteredOn { it.testCase.testClass.endsWith("ProbeTest") }
            .extracting({ it.testCase.testClass }, { it.testCase.testMethod }, { it.testStatus })
            .containsExactly(tuple("TestServicesProbeTest", "writes a test output file through test services", PASSED))
    }

    private fun deviceRunBucket(): String =
        checkNotNull(System.getenv(BUCKET_VARIABLE)) { "Set $BUCKET_VARIABLE to an existing Cloud Storage bucket." }

    private fun resultsDirectory(module: String): Path =
        gradleRunner.root.project(module).buildDir / "outputs/androidTest-results/managedDevice/debug/smallPhone"

    private fun testSuiteResult(module: String): TestSuiteResult =
        (resultsDirectory(module) / "test-result.pb").inputStream().use(TestSuiteResult::parseFrom)

    private companion object {
        private const val TEST_TASK = "smallPhoneDebugAndroidTest"
        private const val SETUP_TASK = ":android-application:smallPhoneSetup"
        private const val PREPARE_PUSHED_FILES_TASK_NAME = "deviceRunPreparePushedFiles"
        private const val PREPARE_PUSHED_FILES_TASK = ":android-application:$PREPARE_PUSHED_FILES_TASK_NAME"
        private const val BUCKET_VARIABLE = "FUNCTIONAL_TEST_DEVICE_RUN_BUCKET"
        private const val UNSUPPORTED_OPTION_PROBLEM = "device-run:unsupported-test-option"
        private val MODULES = listOf("android-application", "android-library", "android-test")
        private val ORCHESTRATOR_EXECUTION =
            """
            android {
                defaultConfig {
                    testInstrumentationRunnerArguments["clearPackageData"] = "true"
                }
                testOptions {
                    execution = "ANDROIDX_TEST_ORCHESTRATOR"
                }
            }
            
            dependencies {
                androidTestUtil(libs.androidx.test.orchestrator)
                androidTestUtil(libs.androidx.test.services.app)
            }
            """.trimIndent()
        private val TEST_SERVICES_PROBE =
            """
            android {
                sourceSets.getByName("androidTest").kotlin.directories += "src/testServicesProbe/kotlin"
            }
            
            dependencies {
                androidTestImplementation(libs.androidx.test.runner)
                androidTestImplementation(libs.androidx.test.services.storage)
                androidTestUtil(libs.androidx.test.services.app)
            }
            """.trimIndent()
        private val ORCHESTRATOR_PROBE =
            """
            android {
                defaultConfig.testInstrumentationRunnerArguments["expectedOrchestratorVersion"] =
                    libs.versions.androidx.test.orchestrator.get()
                sourceSets.getByName("androidTest").kotlin.directories += "src/orchestratorProbe/kotlin"
            }
            """.trimIndent()
    }
}
