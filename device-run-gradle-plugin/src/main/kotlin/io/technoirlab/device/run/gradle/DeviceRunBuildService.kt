package io.technoirlab.device.run.gradle

import io.technoirlab.device.run.core.DefaultRunnerFactory
import io.technoirlab.device.run.core.DeviceRunner
import org.gradle.api.invocation.Gradle
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.kotlin.dsl.registerIfAbsent

/**
 * A Gradle build service that runs tests on Device Run for all projects of a build with one [DeviceRunner].
 *
 * The runner owns the build-wide state: the HTTP transport, the Application Default Credentials and the project they select, the
 * API clients, and the sessions submitted by the build. Project options are passed with each operation, and tasks can use the
 * service concurrently. The runner creates its state when a task first needs Google Cloud, so creating the service loads no
 * credentials. Closing the service at the end of the build cancels the sessions that may still run.
 *
 * Tasks that use the service access Google Cloud even while Gradle runs in offline mode. Builds that run none of them send no
 * requests.
 */
internal abstract class DeviceRunBuildService :
    BuildService<DeviceRunBuildService.Parameters>,
    AutoCloseable {
    internal val runner = parameters.runnerFactory.get().create()

    override fun close() {
        runner.close()
    }

    /**
     * Parameters of [DeviceRunBuildService].
     */
    interface Parameters : BuildServiceParameters {
        val runnerFactory: Property<DeviceRunner.Factory>
    }

    companion object {
        const val NAME = "deviceRun"

        private const val CLIENT_NAME = "Device Run Gradle Plugin"

        /**
         * Registers the build's [DeviceRunBuildService], or returns the service that is already registered.
         *
         * Every project of a build must load the plugin through the same class loader, which [DeviceRunSettingsPlugin] provides.
         * A copy loaded by another class loader cannot use this registration.
         *
         * @param runnerFactory creates the runner of a newly registered service.
         */
        fun registerIfAbsent(
            gradle: Gradle,
            runnerFactory: DeviceRunner.Factory = DefaultRunnerFactory(CLIENT_NAME),
        ): Provider<DeviceRunBuildService> = gradle.sharedServices.registerIfAbsent(NAME, DeviceRunBuildService::class) {
            parameters.runnerFactory.set(runnerFactory)
        }
    }
}
