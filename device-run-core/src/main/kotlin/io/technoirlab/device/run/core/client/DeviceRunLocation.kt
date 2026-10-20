package io.technoirlab.device.run.core.client

/**
 * The Device Run location of a Google Cloud project, which owns its catalog, sessions, and operations.
 */
internal data class DeviceRunLocation(
    val projectId: String,
) {
    /**
     * The resource name of the location, `projects/<project>/locations/global`.
     */
    val name = "projects/$projectId/locations/$LOCATION"

    /**
     * Returns the resource name of a catalog device.
     */
    fun deviceName(deviceId: String): String = "$name/devices/$deviceId"

    /**
     * Returns the resource name of a session.
     */
    fun sessionName(sessionId: String): String = "$name/sessions/$sessionId"

    private companion object {
        private const val LOCATION = "global"
    }
}
