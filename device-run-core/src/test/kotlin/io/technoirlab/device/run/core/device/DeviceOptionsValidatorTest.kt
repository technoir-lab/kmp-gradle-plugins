package io.technoirlab.device.run.core.device

import io.technoirlab.device.run.core.DeviceRunException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class DeviceOptionsValidatorTest {
    private val validator = DeviceOptionsValidator()

    @Test
    fun `devices need a catalog device ID and a language-region locale`() {
        val device = DeviceOptions(name = "pixel", deviceId = " ", locale = "en_US")

        assertThatThrownBy { validator.validate(device) }
            .isInstanceOf(DeviceRunException::class.java)
            .hasMessage(
                """
                Invalid Device Run configuration:
                - deviceRun.devices["pixel"].deviceId is required; set it to the ID of a Device Run catalog device.
                - deviceRun.devices["pixel"].locale 'en_US' must have the form language-region, such as en-US.
                """.trimIndent(),
            )
    }
}
