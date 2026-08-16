package io.technoirlab.cmake.import.internal

import org.gradle.process.ExecOperations
import java.nio.file.Path
import kotlin.io.path.absolutePathString

internal class CMakeRunner(
    private val execOperations: ExecOperations,
) {
    fun generate(projectDir: Path, configureDir: Path, buildType: String, defines: Map<String, String>) {
        execOperations.exec {
            executable = "cmake"
            args(
                "-S",
                projectDir.absolutePathString(),
                "-B",
                configureDir.absolutePathString(),
                "-DCMAKE_BUILD_TYPE=$buildType",
            )
            defines.toSortedMap().forEach { (name, value) ->
                args("-D$name=$value")
            }
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
