package io.technoirlab.device.run.core.testrun.results

import java.nio.file.Path

/**
 * A downloaded session artifact.
 *
 * @property relativePath the path of the artifact below the session directory, such as `mediumPhone/execution-000/logcat.txt`.
 */
internal data class MirroredArtifact(
    val relativePath: String,
    val path: Path,
)
