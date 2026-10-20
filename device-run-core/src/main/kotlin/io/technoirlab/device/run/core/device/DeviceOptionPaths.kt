package io.technoirlab.device.run.core.device

/**
 * Returns the DSL path of the device named [name], which identifies the device in errors.
 */
internal fun deviceOptionPath(name: String): String = "deviceRun.devices[\"$name\"]"
