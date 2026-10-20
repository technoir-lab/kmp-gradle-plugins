package io.technoirlab.device.run.core.testrun

import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import io.technoirlab.device.run.core.files.PROBE_PUSHED_FILE
import java.nio.file.Path

/**
 * Returns a request to run the tests of [testApk], which test [testedApks], for the `freeDebug` variant of the project `:app` on the
 * device `mediumPhone` and to write their results to [resultsDirectory].
 *
 * The device is the catalog device [MEDIUM_PHONE_SNAPSHOT], which receives [PROBE_PUSHED_FILE], and the session keeps its inputs and
 * outputs in the bucket `example-bucket`.
 */
fun appTestRunRequest(testApk: Path, testedApks: List<Path>, resultsDirectory: Path): TestRunRequest = TestRunRequest(
    options = RunOptions(bucket = "example-bucket"),
    device = DeviceOptions(name = "mediumPhone", deviceId = MEDIUM_PHONE_SNAPSHOT.deviceId),
    catalogDevice = MEDIUM_PHONE_SNAPSHOT,
    pushedFiles = listOf(PROBE_PUSHED_FILE),
    testApk = testApk,
    testedApks = testedApks,
    instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
    instrumentationRunnerArguments = emptyMap(),
    name = ":app freeDebug",
    labels = mapOf("project" to ":app", "variant" to "freeDebug", "device" to "mediumPhone"),
    reportProperties = mapOf("device" to "mediumPhone", "flavor" to "freeDebug", "project" to ":app"),
    resultsDirectory = resultsDirectory,
)
