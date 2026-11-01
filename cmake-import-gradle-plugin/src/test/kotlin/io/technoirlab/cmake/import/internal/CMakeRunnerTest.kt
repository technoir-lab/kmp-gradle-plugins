package io.technoirlab.cmake.import.internal

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Action
import org.gradle.process.ExecOperations
import org.gradle.process.ExecResult
import org.gradle.process.ExecSpec
import org.gradle.process.JavaExecSpec
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.absolutePathString

class CMakeRunnerTest {
    private val execOperations = object : ExecOperations {
        override fun exec(action: Action<in ExecSpec>): ExecResult = throw UnsupportedOperationException()

        override fun javaexec(action: Action<in JavaExecSpec>): ExecResult = throw UnsupportedOperationException()
    }
    private val cmakeRunner = CMakeRunner(execOperations)
    private val configureDirectory = Path.of("configure")

    @Test
    fun `build arguments limit parallel jobs to available processors`() {
        val arguments = cmakeRunner.buildArguments(
            configureDir = configureDirectory,
            target = "hello",
            buildType = "Release",
            parallelLevel = null,
            availableProcessors = 3,
        )

        assertThat(arguments).containsExactly(
            "--build",
            configureDirectory.absolutePathString(),
            "--target",
            "hello",
            "--config",
            "Release",
            "--parallel",
            "3",
        )
    }

    @Test
    fun `build arguments leave the parallel job limit to CMAKE_BUILD_PARALLEL_LEVEL`() {
        val arguments = cmakeRunner.buildArguments(
            configureDir = configureDirectory,
            target = "hello",
            buildType = "Release",
            parallelLevel = "2",
            availableProcessors = 3,
        )

        assertThat(arguments).containsExactly(
            "--build",
            configureDirectory.absolutePathString(),
            "--target",
            "hello",
            "--config",
            "Release",
        )
    }

    @Test
    fun `build arguments ignore a blank CMAKE_BUILD_PARALLEL_LEVEL`() {
        val arguments = cmakeRunner.buildArguments(
            configureDir = configureDirectory,
            target = "hello",
            buildType = "Release",
            parallelLevel = " ",
            availableProcessors = 3,
        )

        assertThat(arguments).containsSubsequence("--parallel", "3")
    }
}
