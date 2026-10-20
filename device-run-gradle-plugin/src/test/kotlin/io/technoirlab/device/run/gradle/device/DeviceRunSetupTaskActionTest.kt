package io.technoirlab.device.run.gradle.device

import io.technoirlab.device.run.core.RecordingDeviceRunner
import io.technoirlab.device.run.core.device.CatalogDeviceSnapshotSerializer
import io.technoirlab.device.run.core.device.DeviceOptions
import io.technoirlab.device.run.core.device.MEDIUM_PHONE_SNAPSHOT
import io.technoirlab.device.run.gradle.RecordingProblems
import io.technoirlab.device.run.gradle.registerStubDeviceRunService
import io.technoirlab.gradle.asPath
import io.technoirlab.gradle.test.kit.createRootProject
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.file.Directory
import org.gradle.kotlin.dsl.newInstance
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.io.path.createDirectories

class DeviceRunSetupTaskActionTest {
    private lateinit var runner: RecordingDeviceRunner
    private lateinit var outputDirectory: Directory
    private lateinit var input: DeviceRunSetupInput
    private val action = object : DeviceRunSetupTaskAction() {
        override val problems = RecordingProblems()
    }

    @BeforeEach
    fun setUp() {
        val project = createRootProject("device-run-fixture")
        val service = project.gradle.registerStubDeviceRunService()
        runner = service.get().runner as RecordingDeviceRunner
        outputDirectory = project.layout.buildDirectory.dir("setup").get()
        outputDirectory.asPath().createDirectories()
        input = project.objects.newInstance<DeviceRunSetupInput>().apply {
            deviceName.set("mediumPhone")
        }
    }

    @Test
    fun `records the catalog profile of the device for its test tasks`() {
        input.deviceId.set("mediumphone-arm-35")
        runner.catalogDevice = MEDIUM_PHONE_SNAPSHOT

        action.setup(input, outputDirectory)

        val serializer = CatalogDeviceSnapshotSerializer()
        val snapshot = serializer.read(outputDirectory.file(serializer.fileName("mediumPhone")).asPath())
        assertThat(snapshot).isEqualTo(MEDIUM_PHONE_SNAPSHOT)
        assertThat(runner.resolvedDevices).containsExactly(DeviceOptions(name = "mediumPhone", deviceId = "mediumphone-arm-35"))
    }

    @Test
    fun `leaves the validation of a missing catalog device ID to the runner`() {
        runner.catalogDevice = MEDIUM_PHONE_SNAPSHOT

        action.setup(input, outputDirectory)

        assertThat(runner.resolvedDevices).containsExactly(DeviceOptions(name = "mediumPhone", deviceId = null))
    }
}
