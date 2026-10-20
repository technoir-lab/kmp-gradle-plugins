package io.technoirlab.device.run.core.files

/**
 * The DSL path of the files pushed to the device, which identifies them in errors.
 */
internal const val PUSHED_FILES_OPTION = "deviceRun.files.push"

/**
 * Returns the DSL path of the pushed file named [name], which identifies the file in errors.
 */
internal fun pushedFileOptionPath(name: String): String = "$PUSHED_FILES_OPTION[\"$name\"]"
