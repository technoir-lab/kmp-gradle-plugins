package io.technoirlab.vfsoverlay

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

@DisableCachingByDefault
internal abstract class GenerateVfsOverlayFileTask : DefaultTask() {
    @get:Internal
    abstract val mappings: MapProperty<File, File>

    @get:OutputFile
    abstract val overlayFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val outputFile = overlayFile.get().asFile
        outputFile.parentFile.mkdirs()
        VfsOverlayGenerator().generate(outputFile, mappings.get())
    }
}
