package io.technoirlab.vfsoverlay

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import java.io.File

internal class VfsOverlayGenerator {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun generate(outputFile: File, mappings: Map<File, File>) {
        val overlay = VfsOverlayJson(
            roots = mappings.map { (from, to) ->
                VfsOverlayRootJson(
                    name = from.normalizedPath(),
                    type = "directory-remap",
                    externalContents = to.normalizedPath()
                )
            }
        )
        outputFile.outputStream().buffered().use { output ->
            json.encodeToStream(overlay, output)
        }
    }

    private fun File.normalizedPath(): String =
        absolutePath.replace("\\", "/")
}
