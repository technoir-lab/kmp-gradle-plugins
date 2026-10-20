package io.technoirlab.device.run.gradle

import io.technoirlab.device.run.core.DeviceRunner
import io.technoirlab.device.run.core.InvalidOptionsException
import io.technoirlab.device.run.core.device.DeviceOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.InvalidUserDataException
import org.junit.jupiter.api.AutoClose
import org.junit.jupiter.api.Test
import java.io.IOException

class InvalidOptionsTest {
    private val problems = RecordingProblems()

    // The runner validates options before it connects to Google Cloud.
    @AutoClose
    private val runner = DeviceRunner()

    @Test
    fun `fails with a problem for each invalid option that the runner rejects`() {
        val device = DeviceOptions(name = "pixel", deviceId = " ", locale = "en_US")

        assertThatThrownBy { problems.reporter.reportingInvalidOptions { runner.resolveDevice(device) } }
            .isInstanceOf(InvalidUserDataException::class.java)
            .hasMessage("Invalid Device Run configuration")
            .hasCauseInstanceOf(InvalidOptionsException::class.java)
        assertThat(problems.thrown).containsExactly(
            RecordedProblem(
                id = "device-run:invalid-option",
                contextualLabel = "deviceRun.devices[\"pixel\"].deviceId is required; set it to the ID of a Device Run catalog device.",
            ),
            RecordedProblem(
                id = "device-run:invalid-option",
                contextualLabel = "deviceRun.devices[\"pixel\"].locale 'en_US' must have the form language-region, such as en-US.",
            ),
        )
    }

    @Test
    fun `keeps other failures of the runner`() {
        val failure = IOException("Unable to load credentials")

        assertThatThrownBy { problems.reporter.reportingInvalidOptions { throw failure } }.isSameAs(failure)
        assertThat(problems.thrown).isEmpty()
    }
}
