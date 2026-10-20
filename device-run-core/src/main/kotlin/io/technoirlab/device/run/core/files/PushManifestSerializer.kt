package io.technoirlab.device.run.core.files

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Stores the manifest of the files pushed to the device, with the Cloud Storage objects that hold them, as JSON.
 */
class PushManifestSerializer {
    private val json = Json {
        prettyPrint = true
    }

    /**
     * Writes the manifest of [files] to [path], replacing an existing file.
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun write(files: List<PreparedFile>, path: Path) {
        val manifest = PushManifest(files.map { PushManifest.Entry(it.name, it.uri, it.checksum, it.destinationPath) })
        path.outputStream().use { output -> json.encodeToStream(manifest, output) }
    }

    /**
     * Reads the files of a manifest written by [write].
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun read(path: Path): List<PreparedFile> {
        val manifest = path.inputStream().use { input -> json.decodeFromStream<PushManifest>(input) }
        return manifest.files.map { PreparedFile(it.name, it.uri, it.checksum, it.destinationPath) }
    }
}
