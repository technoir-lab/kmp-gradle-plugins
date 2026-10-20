package io.technoirlab.device.run.gradle.testrun

import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.TestOptions
import com.android.build.api.instrumentation.manageddevice.DeviceTestRunConfigureAction
import io.technoirlab.device.run.core.testrun.RunOptions
import io.technoirlab.device.run.gradle.api.DeviceRunDevice
import io.technoirlab.device.run.gradle.api.DeviceRunExtension
import io.technoirlab.device.run.gradle.files.PreparePushedFilesTask
import io.technoirlab.gradle.setDisallowChanges
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.newInstance
import javax.inject.Inject

@Suppress("UnstableApiUsage")
internal open class DeviceRunTestRunConfigureAction @Inject constructor(
    private val objectFactory: ObjectFactory,
    private val project: Project,
) : DeviceTestRunConfigureAction<DeviceRunDevice, DeviceRunTestRunInput> {
    override fun configureTaskInput(deviceDSL: DeviceRunDevice): DeviceRunTestRunInput =
        objectFactory.newInstance<DeviceRunTestRunInput>().apply {
            configureFrom(deviceDSL)
        }

    private fun DeviceRunTestRunInput.configureFrom(device: DeviceRunDevice) {
        locale.setDisallowChanges(device.locale)
        orientation.setDisallowChanges(device.orientation)
        buildName.setDisallowChanges(project.isolated.rootProject.name)
        options.configureFrom(project.extensions.getByType<DeviceRunExtension>())
        options.configureHelperApks(project.extensions.getByType<CommonExtension>().testOptions)
        val preparePushedFiles = project.tasks.named<PreparePushedFilesTask>(PreparePushedFilesTask.NAME)
        pushManifest.setDisallowChanges(preparePushedFiles.flatMap { it.manifestFile })
    }

    private fun DeviceRunOptionsInput.configureFrom(extension: DeviceRunExtension) {
        bucket.set(extension.storage.bucket)
        waitTimeout.set(extension.waitTimeout)
        instrumentationTimeout.set(extension.instrumentationTimeout)
        labels.set(extension.labels)
        shardingStrategy.set(extension.sharding.strategy)
        shardCount.set(extension.sharding.uniform.shardCount)
        shardTargetDuration.set(extension.sharding.smart.targetDuration)
        timingRecordName.set(extension.sharding.smart.timingRecordName)
        maxShardCount.set(extension.sharding.smart.maxShardCount)
        maxAttempts.set(extension.retries.maxAttempts)
        parallelRetries.set(extension.retries.parallel)
        retryScope.set(extension.retries.scope)
        pathsToPull.set(extension.files.pathsToPull)
        logcat.set(extension.capture.logcat)
        video.set(extension.capture.video)
    }

    /**
     * Runs the tests with Android Test Orchestrator when the AGP test execution option selects either orchestrator, as AGP does,
     * in the version of `androidx.test:orchestrator` in `androidTestUtil` or else the default version of Device Run.
     *
     * Device Run installs its own test services for every test run and its own orchestrator for tests that run with one, so the
     * APKs of both in `androidTestUtil` are supplied rather than ignored.
     */
    private fun DeviceRunOptionsInput.configureHelperApks(testOptions: TestOptions) {
        val orchestratorArtifacts = androidTestUtilArtifacts(setOf(ORCHESTRATOR_MODULE))
        orchestratorVersion.setDisallowChanges(
            // AGP reads the execution option back in lower case.
            project.provider { testOptions.execution.uppercase() }
                .filter { execution -> execution in ORCHESTRATOR_EXECUTIONS }
                .flatMap { orchestratorArtifacts }
                .map { artifacts ->
                    val components = artifacts.map { it.id.componentIdentifier }.filterIsInstance<ModuleComponentIdentifier>()
                    components.firstOrNull()?.version ?: RunOptions.Job.AUTO_ORCHESTRATOR_VERSION
                },
        )
        suppliedHelperApks.setFrom(androidTestUtilArtifacts(SUPPLIED_HELPER_MODULES).map { artifacts -> artifacts.map { it.file } })
        suppliedHelperApks.disallowChanges()
    }

    /**
     * Returns the artifacts of [modules], given as `group:name`, that the `androidTestUtil` configuration resolves to.
     */
    private fun androidTestUtilArtifacts(modules: Set<String>): Provider<List<ResolvedArtifactResult>> =
        project.configurations.named(ANDROID_TEST_UTIL_CONFIGURATION)
            .flatMap { it.incoming.artifacts.resolvedArtifacts }
            .map { artifacts ->
                artifacts.filter { artifact ->
                    val component = artifact.id.componentIdentifier
                    component is ModuleComponentIdentifier && "${component.group}:${component.module}" in modules
                }
            }

    private companion object {
        private const val ANDROID_TEST_UTIL_CONFIGURATION = "androidTestUtil"
        private const val ORCHESTRATOR_MODULE = "androidx.test:orchestrator"
        private const val TEST_SERVICES_MODULE = "androidx.test.services:test-services"
        private val ORCHESTRATOR_EXECUTIONS = setOf("ANDROID_TEST_ORCHESTRATOR", "ANDROIDX_TEST_ORCHESTRATOR")
        private val SUPPLIED_HELPER_MODULES = setOf(ORCHESTRATOR_MODULE, TEST_SERVICES_MODULE)
    }
}
