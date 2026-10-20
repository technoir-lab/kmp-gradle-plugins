package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.manageddevice.TestRunData
import java.io.File

/**
 * Returns the AGP test options of [testRunData] that Device Run devices do not apply, each described by a sentence without a period.
 *
 * Helper APKs from `androidTestUtil` are not installed, apart from [suppliedHelperApks], whose packages Device Run supplies itself.
 */
@Suppress("UnstableApiUsage")
internal fun ignoredTestOptions(testRunData: TestRunData, suppliedHelperApks: Set<File>): List<String> = buildList {
    val helperApks = testRunData.helperApks - suppliedHelperApks
    if (helperApks.isNotEmpty()) {
        add("Helper APKs from androidTestUtil (${helperApks.joinToString { it.name }}) are not installed")
    }
    if (testRunData.additionalInstallOptions.isNotEmpty()) {
        add("Installation options ${testRunData.additionalInstallOptions.joinToString(" ")} are not applied")
    }
    if (testRunData.testData.animationsDisabled) {
        add("Animations are not disabled")
    }
    if (testRunData.testData.isTestCoverageEnabled) {
        add("Test coverage is not collected")
    }
}
