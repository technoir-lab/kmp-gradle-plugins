package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.instrumentation.manageddevice.DeviceTestRunParameters
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunTaskAction
import com.android.build.api.instrumentation.manageddevice.TestRunData
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshot
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshotSerializer
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.files.PushManifestSerializer
import io.technoirlab.device.run.core.testrun.RunOptions
import io.technoirlab.device.run.core.testrun.TestRunRequest
import io.technoirlab.device.run.gradle.DeviceRunExtensionImpl
import io.technoirlab.device.run.gradle.DeviceRunProblems
import io.technoirlab.device.run.gradle.api.ShardingStrategy
import io.technoirlab.device.run.gradle.reportingInvalidOptions
import io.technoirlab.gradle.asPath
import org.gradle.api.InvalidUserDataException
import org.gradle.api.problems.Problems
import java.io.File
import java.nio.file.Path
import javax.inject.Inject

/**
 * Runs the tests of a device in a Device Run session and writes the results where AGP expects them.
 *
 * The tested application is installed from the APKs that AGP selects for the catalog device, after checking that the device
 * runs the minimum API level of the tests. AGP test options that Device Run devices do not apply are reported as warnings.
 */
@Suppress("UnstableApiUsage")
internal abstract class DeviceRunTestRunTaskAction : DeviceTestRunTaskAction<DeviceRunTestRunInput> {
    @get:Inject
    abstract val problems: Problems

    override fun runTests(params: DeviceTestRunParameters<DeviceRunTestRunInput>): Boolean {
        val input = params.deviceInput
        val testRunData = params.testRunData
        val testData = testRunData.testData
        val runner = input.buildService.get().runner
        val snapshotSerializer = CatalogDeviceSnapshotSerializer()
        val setupSnapshot = params.setupResult.file(snapshotSerializer.fileName(testRunData.deviceName)).get().asPath()
        val catalogDevice = snapshotSerializer.read(setupSnapshot)
        val device = DeviceOptions(
            name = testRunData.deviceName,
            deviceId = catalogDevice.deviceId,
            locale = input.locale.orNull,
            orientation = input.orientation.orNull?.toCoreDeviceOrientation(),
        )
        checkMinSdk(testRunData, catalogDevice)
        reportUnsupportedOptions(testRunData, input.options.suppliedHelperApks.files)
        val variantName = variantName(testRunData)
        val defaultRecordName = defaultTimingRecordName(input.buildName.get(), testRunData.projectPath, variantName, catalogDevice.deviceId)
        val request = TestRunRequest(
            options = input.options.toRunOptions(defaultRecordName),
            device = device,
            catalogDevice = catalogDevice,
            pushedFiles = PushManifestSerializer().read(input.pushManifest.get().asPath()),
            testApk = testData.testApk.toPath(),
            testedApks = testedApks(testRunData, catalogDevice, device.locale),
            instrumentationRunner = testData.instrumentationRunner,
            instrumentationRunnerArguments = testData.instrumentationRunnerArguments,
            name = "${testRunData.projectPath} $variantName",
            labels = labels(testRunData, variantName),
            reportProperties = reportProperties(testRunData, variantName),
            resultsDirectory = testRunData.outputDirectory.asPath(),
        )
        return problems.reporter.reportingInvalidOptions { runner.runTests(request) }
    }

    private fun checkMinSdk(testRunData: TestRunData, catalogDevice: CatalogDeviceSnapshot) {
        val apiLevel = catalogDevice.apiLevel
        val minSdk = testRunData.testData.minSdkVersion.apiLevel
        if (apiLevel < minSdk) {
            val failure = InvalidUserDataException("Device ${testRunData.deviceName} cannot run the tests of ${testRunData.projectPath}.")
            throw problems.reporter.throwing(failure, DeviceRunProblems.API_LEVEL_BELOW_MIN_SDK) {
                contextualLabel(
                    "Catalog device ${catalogDevice.deviceId} runs API level $apiLevel, but the tests require at least API level $minSdk",
                )
                solution("Set ${deviceIdProperty(testRunData)} to a catalog device that runs API level $minSdk or higher.")
            }
        }
    }

    private fun reportUnsupportedOptions(testRunData: TestRunData, suppliedHelperApks: Set<File>) {
        for (ignoredOption in ignoredTestOptions(testRunData, suppliedHelperApks)) {
            problems.reporter.report(DeviceRunProblems.UNSUPPORTED_TEST_OPTION) {
                contextualLabel("$ignoredOption for the tests of ${testRunData.projectPath}")
            }
        }
    }

    /**
     * Returns the APKs of the tested application that AGP selects for the catalog device.
     *
     * Library tests instrument the test APK itself, so AGP provides no tested application for them.
     */
    private fun testedApks(testRunData: TestRunData, catalogDevice: CatalogDeviceSnapshot, locale: String?): List<Path> {
        val testData = testRunData.testData
        if (testData.isLibrary) {
            return emptyList()
        }
        val testedApks = testData.testedApkFinder(CatalogDeviceConfigProvider(catalogDevice, locale))
        if (testedApks.isEmpty()) {
            val failure = InvalidUserDataException(
                "Device ${testRunData.deviceName} cannot install the tested application of ${testRunData.projectPath}.",
            )
            throw problems.reporter.throwing(failure, DeviceRunProblems.NO_TESTED_APK) {
                contextualLabel(
                    "AGP selected no APK of the tested application for catalog device ${catalogDevice.deviceId}, which supports ABIs " +
                        "${catalogDevice.abis.joinToString()} at API level ${catalogDevice.apiLevel}",
                )
                solution(
                    "Build the tested application for an ABI of catalog device ${catalogDevice.deviceId}, or set " +
                        "${deviceIdProperty(testRunData)} to a catalog device that supports an ABI of the application.",
                )
            }
        }
        return testedApks.map { it.toPath() }
    }

    /**
     * Returns the DSL property that selects the catalog device of [TestRunData.deviceName].
     */
    private fun deviceIdProperty(testRunData: TestRunData): String =
        "${DeviceRunExtensionImpl.NAME}.devices[\"${testRunData.deviceName}\"].deviceId"

    /**
     * Returns the labels that identify the tests of a test task on their Device Run job, which keep the original project path.
     */
    private fun labels(testRunData: TestRunData, variantName: String): Map<String, String> = mapOf(
        LabelNames.PROJECT to testRunData.projectPath,
        LabelNames.VARIANT to variantName,
        LabelNames.DEVICE to testRunData.deviceName,
    )

    /**
     * Returns the JUnit suite properties that AGP reads for its HTML test report: the device, the tested variant, which AGP reads
     * as the flavor and groups results by, and the project.
     */
    private fun reportProperties(testRunData: TestRunData, variantName: String): Map<String, String> = mapOf(
        LabelNames.DEVICE to testRunData.deviceName,
        LabelNames.FLAVOR to variantName,
        LabelNames.PROJECT to testRunData.projectPath,
    )

    /**
     * Returns the name of the tested variant.
     *
     * AGP passes the product flavor as [TestRunData.variantName], so the variant comes from the name of the test task,
     * `<device><Variant>AndroidTest`, which ends [TestRunData.testRunId].
     */
    private fun variantName(testRunData: TestRunData): String {
        val taskName = testRunData.testRunId.substringAfterLast(':')
        if (!taskName.startsWith(testRunData.deviceName) || !taskName.endsWith(TASK_NAME_SUFFIX)) {
            return testRunData.variantName
        }
        val variant = taskName.removePrefix(testRunData.deviceName).removeSuffix(TASK_NAME_SUFFIX)
        return variant.replaceFirstChar { it.lowercaseChar() }.ifEmpty { testRunData.variantName }
    }

    /**
     * Returns the options of the test run, whose smart sharding uses [defaultTimingRecordName] unless a timing record is named.
     */
    private fun DeviceRunOptionsInput.toRunOptions(defaultTimingRecordName: String): RunOptions = RunOptions(
        bucket = bucket.orNull,
        waitTimeout = waitTimeout.orNull,
        job = RunOptions.Job(
            instrumentationTimeout = instrumentationTimeout.orNull,
            orchestratorVersion = orchestratorVersion.orNull,
            labels = labels.get(),
            sharding = toSharding(defaultTimingRecordName),
            retries = RunOptions.Retries(maxAttempts.orNull, parallelRetries.orNull, retryScope.orNull?.toTestReductionMode()),
            pathsToPull = pathsToPull.get(),
            capture = RunOptions.Capture(logcat.orNull?.toArtifactCapture(), video.orNull?.toArtifactCapture()),
        ),
    )

    private fun DeviceRunOptionsInput.toSharding(defaultTimingRecordName: String): RunOptions.Sharding =
        when (shardingStrategy.orNull ?: ShardingStrategy.NONE) {
            ShardingStrategy.NONE -> RunOptions.Sharding.None
            ShardingStrategy.SMART -> RunOptions.Sharding.Smart(
                targetedShardDuration = shardTargetDuration.orNull,
                timingRecordName = timingRecordName.orNull ?: defaultTimingRecordName,
                maxShardCount = maxShardCount.orNull,
            )
            ShardingStrategy.UNIFORM -> RunOptions.Sharding.Uniform(shardCount.orNull)
        }

    private companion object {
        private const val TASK_NAME_SUFFIX = "AndroidTest"
    }
}
