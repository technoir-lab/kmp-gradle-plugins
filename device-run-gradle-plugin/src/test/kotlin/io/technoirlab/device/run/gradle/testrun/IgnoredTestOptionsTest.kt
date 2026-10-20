package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.manageddevice.TestRunData
import io.technoirlab.gradle.test.kit.createRootProject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class IgnoredTestOptionsTest {
    private val project = createRootProject("device-run-fixture")
    private val orchestratorApk = File("orchestrator-1.6.1.apk")
    private val testServicesApk = File("test-services-1.6.0.apk")
    private val helperApk = File("helper-1.0.apk")

    @Test
    fun `reports the AGP test options that Device Run devices do not apply`() {
        val testRunData = testRunData(
            testData = StubStaticTestData(File("app-androidTest.apk"), animationsDisabled = true, isTestCoverageEnabled = true),
            additionalInstallOptions = listOf("-g", "-r"),
            helperApks = setOf(orchestratorApk, helperApk),
        )

        val ignoredOptions = ignoredTestOptions(testRunData, suppliedHelperApks = emptySet())

        assertThat(ignoredOptions).containsExactly(
            "Helper APKs from androidTestUtil (orchestrator-1.6.1.apk, helper-1.0.apk) are not installed",
            "Installation options -g -r are not applied",
            "Animations are not disabled",
            "Test coverage is not collected",
        )
    }

    @Test
    fun `leaves out the helper APKs that Device Run supplies`() {
        val testRunData = testRunData(helperApks = setOf(orchestratorApk, testServicesApk, helperApk))

        val ignoredOptions = ignoredTestOptions(testRunData, suppliedHelperApks = setOf(orchestratorApk, testServicesApk))

        assertThat(ignoredOptions).containsExactly("Helper APKs from androidTestUtil (helper-1.0.apk) are not installed")
    }

    @Test
    fun `reports nothing when Device Run supplies every helper APK`() {
        val testRunData = testRunData(helperApks = setOf(orchestratorApk, testServicesApk))

        val ignoredOptions = ignoredTestOptions(testRunData, suppliedHelperApks = setOf(orchestratorApk, testServicesApk))

        assertThat(ignoredOptions).isEmpty()
    }

    @Suppress("UnstableApiUsage")
    private fun testRunData(
        testData: StubStaticTestData = StubStaticTestData(File("app-androidTest.apk")),
        additionalInstallOptions: List<String> = emptyList(),
        helperApks: Set<File>,
    ): TestRunData = managedDeviceTestRunData(
        project = project,
        deviceName = "mediumPhone",
        testData = testData,
        additionalInstallOptions = additionalInstallOptions,
        helperApks = helperApks,
    )
}
