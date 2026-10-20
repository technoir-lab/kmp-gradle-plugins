package io.technoirlab.device.run.core.testrun

import com.google.api.services.devicerun.v1alpha.model.AllocationConfig
import com.google.api.services.devicerun.v1alpha.model.AndroidInstallPackagesDeviceAction
import com.google.api.services.devicerun.v1alpha.model.AndroidInstallable
import com.google.api.services.devicerun.v1alpha.model.AndroidInstrumentationTest
import com.google.api.services.devicerun.v1alpha.model.AndroidInstrumentationTestSmartSharding
import com.google.api.services.devicerun.v1alpha.model.AndroidInstrumentationTestUniformSharding
import com.google.api.services.devicerun.v1alpha.model.AndroidLogcatDeviceAction
import com.google.api.services.devicerun.v1alpha.model.AndroidOrientationDeviceAction
import com.google.api.services.devicerun.v1alpha.model.AndroidPullFilesDeviceAction
import com.google.api.services.devicerun.v1alpha.model.AndroidPushFilesDeviceAction
import com.google.api.services.devicerun.v1alpha.model.AndroidPushFilesDeviceActionFileConfig
import com.google.api.services.devicerun.v1alpha.model.AndroidRecordVideoDeviceAction
import com.google.api.services.devicerun.v1alpha.model.AndroidSwitchLocaleDeviceAction
import com.google.api.services.devicerun.v1alpha.model.DeviceAction
import com.google.api.services.devicerun.v1alpha.model.DeviceConfig
import com.google.api.services.devicerun.v1alpha.model.DeviceRequirement
import com.google.api.services.devicerun.v1alpha.model.GcsPath
import com.google.api.services.devicerun.v1alpha.model.InputFile
import com.google.api.services.devicerun.v1alpha.model.JobAction
import com.google.api.services.devicerun.v1alpha.model.JobConfig
import com.google.api.services.devicerun.v1alpha.model.JobSettings
import com.google.api.services.devicerun.v1alpha.model.RetrySettings
import com.google.api.services.devicerun.v1alpha.model.RetrySettingsFlakyTestRetryStrategy
import com.google.api.services.devicerun.v1alpha.model.Session
import com.google.api.services.devicerun.v1alpha.model.SessionConfig
import com.google.api.services.devicerun.v1alpha.model.SessionConfigSessionOutputFileDirectoryConfig
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.testrun.job.ArtifactCapture
import io.technoirlab.device.run.core.testrun.job.JobSpec
import io.technoirlab.device.run.core.testrun.job.RetrySpec
import io.technoirlab.device.run.core.testrun.sharding.ShardingSpec

/**
 * Builds the Device Run session of one test run: one instrumentation job allocated to one device.
 *
 * Shards and retry attempts are executions that Device Run creates within the job. Unset optional options are omitted, so the
 * service applies its own behavior, while the runner's timeout and retry policy is always sent explicitly. Code coverage is not
 * requested.
 */
internal class SessionConfigFactory {
    private val displayNames = DisplayNames()

    /**
     * Creates the session for [spec] and [inputs].
     */
    fun create(spec: RunSpec, inputs: SessionInputs): Session {
        val deviceName = inputs.device.name
        val sessionName = displayNames.sanitize("${inputs.testRunName} $deviceName", fallback = DEFAULT_SESSION_NAME)
        val jobConfig = JobConfig()
            .setDisplayName(displayNames.sanitize(deviceName, fallback = DEFAULT_JOB_NAME))
            // Validation rejects user labels with the keys of the test run labels; the test run labels win regardless.
            .setLabels(spec.job.labels + inputs.labels)
            .setAction(JobAction().setAndroidInstrumentationTest(instrumentationTest(spec.job, inputs)))
            .setAllocationConfig(
                AllocationConfig().setDeviceConfigs(
                    listOf(
                        DeviceConfig()
                            .setRequirement(DeviceRequirement().setDeviceId(inputs.device.deviceId))
                            .setActions(deviceActions(spec.job, inputs).ifEmpty { null }),
                    ),
                ),
            )
            .setSettings(JobSettings().setRetrySettings(RetrySettings().setFlakyTestRetryStrategy(retryStrategy(spec.job.retries))))
        val outputDirectory = SessionConfigSessionOutputFileDirectoryConfig().setGcsOutputDirectory(
            GcsPath().setPath(inputs.outputDirectory.toString()),
        )
        return Session().setSessionConfig(
            SessionConfig()
                .setDisplayName(sessionName)
                .setJobConfigs(listOf(jobConfig))
                .setOutputDirectoryConfig(outputDirectory),
        )
    }

    private fun instrumentationTest(job: JobSpec, inputs: SessionInputs): AndroidInstrumentationTest = AndroidInstrumentationTest()
        .setTestInstallable(installable(listOf(inputs.packages.testApk)))
        .setTestRunnerClass(inputs.testRunnerClass)
        .setTestTargets(inputs.runnerArguments.testTargets.ifEmpty { null })
        .setAdditionalTestOptions(inputs.runnerArguments.additionalTestOptions.ifEmpty { null })
        .setInstrumentationTimeout(job.instrumentationTimeout.toProtoDuration())
        .setOrchestratorVersion(job.orchestratorVersion)
        .setUniformSharding(uniformSharding(job.sharding))
        .setSmartSharding(smartSharding(job.sharding))

    private fun smartSharding(sharding: ShardingSpec): AndroidInstrumentationTestSmartSharding? = when (sharding) {
        ShardingSpec.None, is ShardingSpec.Uniform -> null
        is ShardingSpec.Smart -> AndroidInstrumentationTestSmartSharding()
            .setTargetedShardDuration(sharding.targetedShardDuration.toProtoDuration())
            .setTimingRecord(inputFile(sharding.timingRecord))
            .setMaxShardCount(sharding.maxShardCount)
    }

    private fun uniformSharding(sharding: ShardingSpec): AndroidInstrumentationTestUniformSharding? = when (sharding) {
        ShardingSpec.None, is ShardingSpec.Smart -> null
        is ShardingSpec.Uniform -> AndroidInstrumentationTestUniformSharding().setShardCount(sharding.shardCount)
    }

    private fun retryStrategy(retries: RetrySpec): RetrySettingsFlakyTestRetryStrategy = RetrySettingsFlakyTestRetryStrategy()
        .setFlakyTestAttempts(retries.flakyTestAttempts)
        .setParallelRetry(retries.parallelRetry)
        .setTestReductionMode(retries.testReductionMode.name)

    /**
     * Returns the device actions in the order that gcloud uses, each at most once.
     */
    private fun deviceActions(job: JobSpec, inputs: SessionInputs): List<DeviceAction> = buildList {
        if (job.capture.logcat != ArtifactCapture.DISABLED) {
            add(DeviceAction().setAndroidLogcat(AndroidLogcatDeviceAction()))
        }
        val targetApp = inputs.packages.targetApp
        if (targetApp.isNotEmpty()) {
            add(DeviceAction().setAndroidInstallPackages(AndroidInstallPackagesDeviceAction().setTargetApp(installable(targetApp))))
        }
        if (inputs.pushedFiles.isNotEmpty()) {
            val fileConfigs = inputs.pushedFiles.map { file ->
                AndroidPushFilesDeviceActionFileConfig()
                    .setDestinationPath(file.destinationPath)
                    .setSourceFile(inputFile(file.uri))
            }
            add(DeviceAction().setAndroidPushFiles(AndroidPushFilesDeviceAction().setFileConfigs(fileConfigs)))
        }
        inputs.device.locale?.let { locale ->
            add(DeviceAction().setAndroidSwitchLocale(AndroidSwitchLocaleDeviceAction().setLocaleCode(locale)))
        }
        inputs.device.orientation?.let { orientation ->
            add(DeviceAction().setAndroidOrientation(AndroidOrientationDeviceAction().setOrientation(orientation.apiValue)))
        }
        if (job.pathsToPull.isNotEmpty()) {
            add(DeviceAction().setAndroidPullFiles(AndroidPullFilesDeviceAction().setPaths(job.pathsToPull)))
        }
        if (job.capture.video != ArtifactCapture.DISABLED) {
            val video = AndroidRecordVideoDeviceAction().setDiscardOnPass(job.capture.video == ArtifactCapture.ON_FAILURE)
            add(DeviceAction().setAndroidRecordVideo(video))
        }
    }

    private fun installable(files: List<GcsUri>): AndroidInstallable = AndroidInstallable().setFiles(files.map(::inputFile))

    private fun inputFile(uri: GcsUri): InputFile = InputFile().setGcsInputFile(GcsPath().setPath(uri.toString()))

    private companion object {
        private const val DEFAULT_SESSION_NAME = "tests"
        private const val DEFAULT_JOB_NAME = "device"
    }
}
