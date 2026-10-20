package io.technoirlab.device.run.gradle

import org.gradle.api.problems.ProblemGroup
import org.gradle.api.problems.ProblemId

/**
 * The problem group of the plugin and the IDs of the problems that it reports.
 *
 * Builds and tools match problems by these names, so they stay stable.
 */
@Suppress("UnstableApiUsage")
internal object DeviceRunProblems {
    val GROUP = ProblemGroup.create("device-run", "Device Run plugin")

    val INVALID_OPTION = ProblemId.create("invalid-option", "Invalid Device Run option", GROUP)
    val API_LEVEL_BELOW_MIN_SDK = ProblemId.create("api-level-below-min-sdk", "Device API level below minSdk", GROUP)
    val NO_TESTED_APK = ProblemId.create("no-tested-apk", "No tested APK for the device", GROUP)
    val UNSUPPORTED_TEST_OPTION = ProblemId.create("unsupported-test-option", "Unsupported AGP test option", GROUP)
}
