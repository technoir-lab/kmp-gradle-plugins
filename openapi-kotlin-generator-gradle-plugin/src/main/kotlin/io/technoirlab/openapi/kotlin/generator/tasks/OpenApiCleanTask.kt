package io.technoirlab.openapi.kotlin.generator.tasks

import io.technoirlab.gradle.asPath
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.io.path.Path
import kotlin.io.path.deleteExisting
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

@UntrackedTask(because = "Removes generated files from the project's hand-maintained source directory")
internal abstract class OpenApiCleanTask : DefaultTask() {
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    // The configured manifest path may not exist before the first generation.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:Internal
    abstract val manifestBaseDirectory: DirectoryProperty

    @TaskAction
    fun clean() {
        val manifest = manifestFile.get().asPath()
        if (!manifest.isRegularFile(NOFOLLOW_LINKS)) return

        val baseDirectory = manifestBaseDirectory.get().asPath()
        val directory = baseDirectory.toRealPath()
        val sourceDirectory = (directory / baseDirectory.relativize(this.sourceDirectory.get().asPath())).normalize()
        for (entry in manifest.readLines()) {
            val relativePath = Path(entry)
            if (relativePath.isAbsolute) continue
            val file = (directory / relativePath).normalize()
            if (!file.startsWith(sourceDirectory) || file.extension != "kt") continue
            if (!file.isRegularFile(NOFOLLOW_LINKS)) continue
            if (!file.toRealPath().startsWith(sourceDirectory)) continue
            file.deleteExisting()
        }
    }
}
