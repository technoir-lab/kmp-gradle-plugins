package io.technoirlab.device.run.gradle.device

import io.technoirlab.device.run.gradle.DeviceRunBuildService
import io.technoirlab.device.run.gradle.api.DeviceRunDevice
import io.technoirlab.device.run.gradle.assertFinal
import io.technoirlab.gradle.test.kit.createRootProject
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.kotlin.dsl.newInstance
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceRunSetupConfigureActionTest {
    private lateinit var project: Project
    private lateinit var action: DeviceRunSetupConfigureAction

    @BeforeEach
    fun setUp() {
        project = createRootProject("device-run-fixture")
        action = DeviceRunSetupConfigureAction(project.objects)
    }

    @Test
    fun `task inputs follow device properties configured after input creation`() {
        val device = project.objects.newInstance<DeviceRunDevice>("mediumPhone")
        val input = action.configureTaskInput(device)

        device.deviceId.set("mediumphone-arm-35")

        assertThat(input.deviceName.get()).isEqualTo("mediumPhone")
        assertThat(input.deviceId.get()).isEqualTo("mediumphone-arm-35")
        assertFinal(input.deviceName, "otherPhone")
        assertFinal(input.deviceId, "smallphone-arm-35")
    }

    @Test
    fun `task inputs reference the registered build service`() {
        val service = DeviceRunBuildService.registerIfAbsent(project.gradle)
        val device = project.objects.newInstance<DeviceRunDevice>("mediumPhone")

        val input = action.configureTaskInput(device)

        assertThat(input.buildService.get()).isSameAs(service.get())
    }
}
