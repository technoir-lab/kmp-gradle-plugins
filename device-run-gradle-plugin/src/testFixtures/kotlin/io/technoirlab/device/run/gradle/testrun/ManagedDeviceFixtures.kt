package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.StaticTestData
import com.android.build.api.instrumentation.manageddevice.TestRunData
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshotSerializer
import org.gradle.api.Project
import org.gradle.api.file.Directory
import java.io.File
import kotlin.io.path.createParentDirectories

/**
 * Returns the data that AGP 9.4.1 passes to the test task of the managed device [deviceName] for the tests of [testData] in the
 * [buildType] of [project].
 *
 * As in AGP, the variant name is the product flavor of the tests, which is empty without flavors, the test run ID is the path of the
 * test task, `<device><Variant>AndroidTest`, and the results and coverage directories are those of the device in the variant.
 */
@Suppress("UnstableApiUsage")
internal fun managedDeviceTestRunData(
    project: Project,
    deviceName: String,
    testData: StaticTestData,
    buildType: String = "debug",
    additionalInstallOptions: List<String> = emptyList(),
    helperApks: Set<File> = emptySet(),
): TestRunData {
    val flavor = testData.flavorName
    val variant = if (flavor.isEmpty()) buildType else flavor + buildType.replaceFirstChar(Char::uppercaseChar)
    val deviceDirectory = if (flavor.isEmpty()) "$buildType/$deviceName" else "$buildType/flavors/$flavor/$deviceName"
    return StubTestRunData(
        testData = testData,
        outputDirectory = project.layout.buildDirectory.dir("$RESULTS_DIRECTORY/$deviceDirectory").get(),
        coverageOutputDirectory = project.layout.buildDirectory.dir("$COVERAGE_DIRECTORY/$deviceDirectory").get(),
        variantName = flavor,
        testRunId = project.absoluteProjectPath("$deviceName${variant.replaceFirstChar(Char::uppercaseChar)}AndroidTest"),
        deviceName = deviceName,
        projectPath = project.path,
        additionalTestOutputDir = null,
        additionalInstallOptions = additionalInstallOptions,
        helperApks = helperApks,
    )
}

/**
 * Returns the directory in which AGP keeps the setup results of the managed device [deviceName] of [project], after the setup task
 * of the device recorded [catalogDevice] there.
 */
internal fun managedDeviceSetupResults(project: Project, deviceName: String, catalogDevice: CatalogDeviceSnapshot): Directory {
    val directory = project.layout.buildDirectory.dir("$SETUP_RESULTS_DIRECTORY/$deviceName").get()
    val serializer = CatalogDeviceSnapshotSerializer()
    serializer.write(catalogDevice, directory.file(serializer.fileName(deviceName)).asFile.toPath().createParentDirectories())
    return directory
}

private const val RESULTS_DIRECTORY = "outputs/androidTest-results/managedDevice"
private const val COVERAGE_DIRECTORY = "outputs/managed_device_code_coverage"
private const val SETUP_RESULTS_DIRECTORY = "managedDeviceSetupResults"
