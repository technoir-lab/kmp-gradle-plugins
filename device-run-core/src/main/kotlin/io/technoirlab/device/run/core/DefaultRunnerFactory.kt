package io.technoirlab.device.run.core

import java.io.Serial
import java.io.Serializable

/**
 * Creates runners that authenticate with Application Default Credentials, as [DeviceRunner] does.
 *
 * The factory is serializable, so that it can be kept where its owner is persisted, such as in the parameters of a Gradle build
 * service.
 *
 * @param clientName the name of the client that uses the runners, which identifies it in the `User-Agent` header of Device Run and
 * Cloud Storage requests.
 */
class DefaultRunnerFactory(
    private val clientName: String,
) : DeviceRunner.Factory,
    Serializable {
    override fun create(): DeviceRunner = DeviceRunner(clientName)

    private companion object {
        @Serial
        private const val serialVersionUID = 4196087768611563681L
    }
}
