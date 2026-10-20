package io.technoirlab.device.run.gradle.files

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * A file pushed to the device, as configured for [PreparePushedFilesTask].
 *
 * The local file keeps its provider, so the task depends on the task that produces it.
 */
internal interface PushedFileInput {
    @get:Input
    val name: Property<String>

    @get:Input
    @get:Optional
    val gcsUri: Property<String>

    // A missing file is reported with its DSL property when the task runs, instead of failing input validation.
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    val localFile: RegularFileProperty

    @get:Input
    @get:Optional
    val destinationPath: Property<String>
}
