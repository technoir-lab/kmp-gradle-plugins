package io.technoirlab.device.run.core.testrun.session

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.outputStream

/**
 * Stores [SessionMetadata] as JSON, replacing the file atomically so that an interruption never leaves a partial file.
 */
internal class SessionMetadataSerializer {
    private val json = Json {
        prettyPrint = true
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun write(metadata: SessionMetadata, path: Path) {
        path.parent.createDirectories()
        val temporaryFile = path.resolveSibling("${path.name}.tmp")
        temporaryFile.outputStream().use { output -> json.encodeToStream(metadata, output) }
        temporaryFile.moveTo(path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun read(path: Path): SessionMetadata = path.inputStream().use { input -> json.decodeFromStream(input) }
}
