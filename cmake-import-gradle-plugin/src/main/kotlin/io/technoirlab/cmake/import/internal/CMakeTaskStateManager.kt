package io.technoirlab.cmake.import.internal

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.moveTo
import kotlin.io.path.outputStream
import kotlin.io.path.readBytes

internal class CMakeTaskStateManager {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    fun create(resetIdentity: String? = null): CMakeTaskState = CMakeTaskState(
        version = STATE_VERSION,
        executionId = UUID.randomUUID().toString(),
        resetIdentity = resetIdentity,
    )

    @OptIn(ExperimentalSerializationApi::class)
    fun readOrNull(path: Path): CMakeTaskState? {
        if (!path.isRegularFile()) return null
        return runCatching {
            path.inputStream().buffered().use { input ->
                json.decodeFromStream<CMakeTaskState>(input)
            }
        }.getOrNull()?.takeIf { it.version == STATE_VERSION }
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun write(path: Path, state: CMakeTaskState) {
        path.createParentDirectories()
        val temporaryFile = createTempFile(path.parent, ".${path.fileName}.", ".tmp")
        try {
            temporaryFile.outputStream().buffered().use { output ->
                json.encodeToStream(state, output)
            }
            try {
                Files.move(
                    temporaryFile,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                temporaryFile.moveTo(path, overwrite = true)
            }
        } finally {
            temporaryFile.deleteIfExists()
        }
    }

    fun calculateResetIdentity(
        generator: String?,
        buildType: String,
        definitions: Map<String, String>,
        sourceDirectory: Path,
        configureDirectory: Path,
        toolchainFile: Path,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")

        fun update(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
            digest.update(0)
            digest.update(bytes)
            digest.update(0)
        }

        update(generator ?: "<default>")
        update(buildType)
        definitions.toSortedMap().forEach { (name, value) ->
            update(name)
            update(value)
        }
        update(sourceDirectory.normalizedPathString())
        update(configureDirectory.normalizedPathString())
        update(toolchainFile.normalizedPathString())
        digest.update(toolchainFile.readBytes())
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        private const val STATE_VERSION = 1
    }
}
