package io.technoirlab.device.run.core.files

import io.technoirlab.device.run.core.InvalidOptionsException
import io.technoirlab.device.run.core.ValidationErrors
import io.technoirlab.device.run.core.storage.GcsUri
import io.technoirlab.device.run.core.storage.validateBucket

/**
 * Validates the files pushed to the device and the device paths pulled after the tests, which follow the same device path rules.
 */
internal class FileOptionsValidator {
    /**
     * Validates the files pushed to the device and the bucket that receives uploaded local files.
     *
     * @throws InvalidOptionsException listing every error of the options.
     */
    fun validatePushedFiles(bucket: String?, files: List<PushedFileOptions>): PushedFilesSpec {
        val errors = ValidationErrors()
        val spec = PushedFilesSpec(
            bucket = errors.validateBucket(bucket),
            files = errors.pushedFiles(files),
        )
        errors.throwIfAny()
        return spec
    }

    /**
     * Validates the device paths pulled after the tests, adding their errors to [errors].
     */
    fun validatePathsToPull(paths: List<String>, errors: ValidationErrors): List<String> {
        if (paths.size > MAX_PULLED_PATHS) {
            errors.add(PATHS_TO_PULL, "has ${paths.size} paths, more than the limit of $MAX_PULLED_PATHS")
        }
        paths.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach { path ->
            errors.add(PATHS_TO_PULL, "lists $path more than once")
        }
        return paths.mapNotNull { errors.devicePath(PATHS_TO_PULL, it) }
    }

    private fun ValidationErrors.pushedFiles(files: List<PushedFileOptions>): List<PushedFileSpec> {
        if (files.size > MAX_PUSHED_FILES) {
            add(PUSHED_FILES_OPTION, "has ${files.size} files, more than the limit of $MAX_PUSHED_FILES")
        }
        files.filter { it.destinationPath != null }
            .groupBy { it.destinationPath }
            .filterValues { it.size > 1 }
            .forEach { (destination, entries) ->
                add(PUSHED_FILES_OPTION, "entries ${entries.joinToString { "'${it.name}'" }} share the destination path $destination")
            }
        return files.mapNotNull { pushedFile(it) }
    }

    private fun ValidationErrors.pushedFile(file: PushedFileOptions): PushedFileSpec? {
        val property = pushedFileOptionPath(file.name)
        val source = pushSource(property, file)
        val destination = devicePath("$property.destinationPath", file.destinationPath)
        return if (source != null && destination != null) PushedFileSpec(file.name, source, destination) else null
    }

    private fun ValidationErrors.pushSource(property: String, file: PushedFileOptions): PushSource? {
        val gcsUri = file.gcsUri
        val localFile = file.localFile
        return when {
            gcsUri != null && localFile != null -> {
                add(property, "sets both gcsUri and localFile; set exactly one source")
                null
            }
            gcsUri != null -> try {
                PushSource.CloudFile(GcsUri.parse(gcsUri))
            } catch (e: IllegalArgumentException) {
                add("$property.gcsUri", e.message.orEmpty())
                null
            }
            localFile != null -> PushSource.LocalFile(localFile)
            else -> {
                add(property, "sets neither gcsUri nor localFile; set exactly one source")
                null
            }
        }
    }

    private fun ValidationErrors.devicePath(property: String, path: String?): String? {
        when {
            path.isNullOrBlank() -> add(property, "is required")
            !path.startsWith('/') -> add(property, "'$path' must be an absolute device path")
            path.split('/').any { it == "." || it == ".." } -> add(property, "'$path' must not contain '.' or '..' segments")
            else -> return path
        }
        return null
    }

    private companion object {
        private const val PATHS_TO_PULL = "deviceRun.files.pathsToPull"

        private const val MAX_PUSHED_FILES = 50
        private const val MAX_PULLED_PATHS = 10
    }
}
