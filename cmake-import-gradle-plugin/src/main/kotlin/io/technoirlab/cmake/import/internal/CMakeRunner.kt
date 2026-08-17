package io.technoirlab.cmake.import.internal

import org.gradle.process.ExecOperations
import java.nio.file.Path
import kotlin.io.path.absolutePathString

internal class CMakeRunner(
    private val execOperations: ExecOperations,
) {
    fun generate(
        projectDir: Path,
        configureDir: Path,
        toolchainFile: Path,
        buildType: String,
        generator: String?,
        defines: Map<String, String>,
    ) {
        execOperations.exec {
            executable = "cmake"
            args(
                "-S",
                projectDir.absolutePathString(),
                "-B",
                configureDir.absolutePathString(),
                "-DCMAKE_BUILD_TYPE=$buildType",
            )
            if (generator != null) {
                args("-G", generator)
            }
            defines.toSortedMap().forEach { (name, value) ->
                args("-D$name=$value")
            }
            args("--toolchain", toolchainFile.absolutePathString())
        }
    }

    fun build(configureDir: Path, targetName: String, buildType: String) {
        execOperations.exec {
            executable = "cmake"
            args(
                "--build",
                configureDir.absolutePathString(),
                "--target",
                targetName,
                "--config",
                buildType,
            )
        }
    }

    fun install(configureDir: Path, installDir: Path, buildType: String, component: String?) {
        execOperations.exec {
            executable = "cmake"
            args(
                "--install",
                configureDir.absolutePathString(),
                "--prefix",
                installDir.absolutePathString(),
                "--config",
                buildType,
            )
            if (component != null) {
                args("--component", component)
            }
        }
    }
}
