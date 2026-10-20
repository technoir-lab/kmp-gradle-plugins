package io.technoirlab.device.run.core

/**
 * A [DeviceRunner] operation cannot complete, for example because its options are invalid or Device Run rejects a request.
 *
 * The message explains the failure and names the configuration that causes it. Invalid options throw [InvalidOptionsException].
 */
open class DeviceRunException internal constructor(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
