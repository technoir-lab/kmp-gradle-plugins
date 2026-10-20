package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.StaticTestData
import com.android.build.api.instrumentation.manageddevice.TestRunData
import org.gradle.api.file.Directory
import java.io.File

/**
 * Test run data of the given values. [managedDeviceTestRunData] supplies the values that AGP passes.
 */
@Suppress("UnstableApiUsage")
internal class StubTestRunData(
    override val testData: StaticTestData,
    override val outputDirectory: Directory,
    override val coverageOutputDirectory: Directory,
    override val variantName: String,
    override val testRunId: String,
    override val deviceName: String,
    override val projectPath: String,
    override val additionalTestOutputDir: Directory?,
    override val additionalInstallOptions: List<String>,
    override val helperApks: Set<File>,
) : TestRunData
