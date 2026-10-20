package io.technoirlab.device.run.core

import java.io.Serial
import java.io.Serializable

/**
 * Creates a [RecordingDeviceRunner], which tests reach through the owner of the runner.
 *
 * The factory is serializable, so that it can replace a [DefaultRunnerFactory] in the parameters of a Gradle build
 * service.
 */
class StubRunnerFactory :
    DeviceRunner.Factory,
    Serializable {
    override fun create(): DeviceRunner = RecordingDeviceRunner()

    private companion object {
        @Serial
        private const val serialVersionUID = 1040738148162423259L
    }
}
