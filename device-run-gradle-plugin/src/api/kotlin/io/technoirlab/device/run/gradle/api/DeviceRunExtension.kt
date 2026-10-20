package io.technoirlab.device.run.gradle.api

import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested
import java.time.Duration

/**
 * Configures devices that run instrumentation tests on Device Run, part of Google's Developer Device Platform.
 *
 * Each test task of a device submits one Device Run session in the Google Cloud project selected by Application Default
 * Credentials and waits for it to finish.
 *
 * The test APK, the tested application, and the instrumentation runner arguments come from AGP. Library tests instrument the test
 * APK itself, so no application is installed. When the AGP test execution option selects Android Test Orchestrator, the tests run
 * with the version of `androidx.test:orchestrator` in `androidTestUtil`, or the default version of Device Run without that
 * dependency. Device Run installs its own test services in place of `androidx.test.services:test-services`. Other AGP helper APKs
 * (`androidTestUtil`), additional install options, disabled animations, and test coverage are not supported.
 */
@DeviceRunDsl
interface DeviceRunExtension {
    /**
     * Container of [devices][DeviceRunDevice] that run tests on Device Run.
     *
     * Devices are synchronized with the managed devices of the Android plugin, `testOptions.managedDevices.allDevices`.
     */
    val devices: NamedDomainObjectContainer<DeviceRunDevice>

    /**
     * The maximum time of one instrumentation run, between 1 minute and 3 hours. Defaults to 5 minutes.
     */
    val instrumentationTimeout: Property<Duration>

    /**
     * Labels of the tests of each test task in Device Run, which Device Run returns in the report of the session. The test results
     * do not include them.
     *
     * The plugin adds the labels `project`, `variant`, and `device`, whose keys these labels must not use. At most 13 labels can
     * be added, with keys of at most 32 bytes and values of at most 1024 bytes.
     */
    val labels: MapProperty<String, String>

    /**
     * The overall time that a test task waits for its tests, including queueing, device setup, test execution, and retries.
     *
     * Tests that do not finish in time are cancelled and fail the test task. Unset waits until the tests finish.
     */
    val waitTimeout: Property<Duration>

    /**
     * Device logs and screen recordings captured from the device.
     */
    @get:Nested
    val capture: CaptureOptions

    /**
     * Files pushed to the device before the tests and paths pulled from it afterwards.
     */
    @get:Nested
    val files: FileOptions

    /**
     * Retries of failed tests.
     */
    @get:Nested
    val retries: RetryOptions

    /**
     * How Device Run splits the tests into shards that run on separate devices. Defaults to a single shard.
     */
    @get:Nested
    val sharding: ShardingOptions

    /**
     * The Cloud Storage bucket for test inputs, session outputs, and smart sharding timing records.
     */
    @get:Nested
    val storage: StorageOptions

    /**
     * Device logs and screen recordings captured from the device.
     */
    fun capture(action: Action<CaptureOptions>) {
        action.execute(capture)
    }

    /**
     * Files pushed to the device before the tests and paths pulled from it afterwards.
     */
    fun files(action: Action<FileOptions>) {
        action.execute(files)
    }

    /**
     * Retries of failed tests.
     */
    fun retries(action: Action<RetryOptions>) {
        action.execute(retries)
    }

    /**
     * How Device Run splits the tests into shards that run on separate devices. Defaults to a single shard.
     */
    fun sharding(action: Action<ShardingOptions>) {
        action.execute(sharding)
    }

    /**
     * The Cloud Storage bucket for test inputs, session outputs, and smart sharding timing records.
     */
    fun storage(action: Action<StorageOptions>) {
        action.execute(storage)
    }
}
