package io.technoirlab.device.run.core.testrun.results

import java.nio.file.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

/**
 * Writes the session [outputs], the contents of the outputs by their paths below the session directory, below [directory] as the
 * runner mirrors them, and returns the mirrored artifacts in the order of their paths, as Cloud Storage lists them.
 */
internal fun mirrorOutputs(directory: Path, outputs: Map<String, String>): List<MirroredArtifact> =
    outputs.toSortedMap().map { (relativePath, content) ->
        MirroredArtifact(relativePath, (directory / relativePath).createParentDirectories().apply { writeText(content) })
    }
