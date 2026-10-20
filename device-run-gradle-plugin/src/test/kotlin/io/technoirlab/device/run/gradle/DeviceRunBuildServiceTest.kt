package io.technoirlab.device.run.gradle

import io.technoirlab.device.run.core.DeviceRunner
import io.technoirlab.device.run.core.RecordingDeviceRunner
import io.technoirlab.device.run.core.files.PushedFileOptions
import io.technoirlab.gradle.test.kit.createRootProject
import org.assertj.core.api.Assertions.assertThat
import org.gradle.kotlin.dsl.newInstance
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeviceRunBuildServiceTest {
    private val runner = RecordingDeviceRunner()
    private var runnerCreations = 0
    private val pushedFile = PushedFileOptions(
        name = "input",
        gcsUri = "gs://example-bucket/input.txt",
        destinationPath = "/sdcard/input.txt",
    )
    private lateinit var parameters: DeviceRunBuildService.Parameters

    @BeforeEach
    fun setUp() {
        val project = createRootProject("device-run-fixture")
        parameters = project.objects.newInstance<DeviceRunBuildService.Parameters>().apply {
            runnerFactory.set(DeviceRunner.Factory { runner.also { runnerCreations++ } })
        }
    }

    @Test
    fun `cloud operations of the build share one runner`() {
        val service = createService()

        service.runner.preparePushedFiles("example-bucket", listOf(pushedFile))
        service.runner.preparePushedFiles("example-bucket", listOf(pushedFile))

        assertThat(runnerCreations).isEqualTo(1)
        assertThat(runner.pushedFiles).containsExactly(pushedFile, pushedFile)
    }

    @Test
    fun `closing the service closes its runner`() {
        val service = createService()

        service.close()

        assertThat(runner.closeCount).isEqualTo(1)
    }

    private fun createService() = object : DeviceRunBuildService() {
        override fun getParameters(): Parameters = this@DeviceRunBuildServiceTest.parameters
    }
}
