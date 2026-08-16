package io.technoirlab.cmake.import

import io.technoirlab.gradle.test.kit.GradleRunnerExtension
import io.technoirlab.gradle.test.kit.appendBuildScript
import io.technoirlab.gradle.test.kit.buildDir
import io.technoirlab.gradle.test.kit.replaceText
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.io.path.div

class CMakeImportPluginFunctionalTest {
    @RegisterExtension
    private val gradleRunner = GradleRunnerExtension("test-project")

    @Test
    fun `default Release application runs without debug output`() {
        val result = gradleRunner.build(":kmp-application:runReleaseExecutable${hostTargetSuffix()}")

        assertThat(result.output)
            .contains("Hello, world!")
            .doesNotContain("Debug build")
    }

    @Test
    fun `Debug application runs with debug output`() {
        gradleRunner.root.project("kmp-application").appendBuildScript(
            """
            cmakeImport {
                buildType = "Debug"
            }
            """.trimIndent(),
        )

        val result = gradleRunner.build(":kmp-application:runDebugExecutable${hostTargetSuffix()}")

        assertThat(result.output)
            .contains("Hello, world!")
            .contains("Debug build")
    }

    @Test
    fun `uses a configured third-party install component`() {
        gradleRunner.root.project("kmp-application").appendBuildScript(
            """
            cmakeImport {
                installComponent = "third-party-devel"
            }
            """.trimIndent(),
        )

        val result = gradleRunner.build(":kmp-application:runReleaseExecutable${hostTargetSuffix()}")

        assertThat(result.output).contains("Hello, world!")
    }

    @Test
    fun `passes configured definitions to CMake`() {
        gradleRunner.root.project("kmp-application").appendBuildScript(
            """
            cmakeImport {
                defines.put("FOO", "bar")
            }
            """.trimIndent(),
        )

        gradleRunner.build(":kmp-application:cmakeGenerate${hostTargetSuffix()}")

        val cache = gradleRunner.root.project("kmp-application").buildDir /
            "intermediates/cmake/${hostTargetName()}/CMakeCache.txt"
        assertThat(cache).content().contains("FOO:UNINITIALIZED=bar")
    }

    @Test
    fun `published library carries the CMake archive to a consumer`() {
        gradleRunner.build(
            ":kmp-library:publishKotlinMultiplatformPublicationToTestRepository",
            ":kmp-library:publish${hostTargetSuffix()}PublicationToTestRepository",
        )

        val result = gradleRunner.build(":kmp-consumer:runReleaseExecutable${hostTargetSuffix()}")

        assertThat(result.output)
            .contains("Hello, world!")
            .doesNotContain("Debug build")
    }

    @Test
    fun `clean build`() {
        val runTask = ":kmp-application:runReleaseExecutable${hostTargetSuffix()}"
        gradleRunner.build(runTask)
        val cleanResult = gradleRunner.build(":kmp-application:clean")
        val runResult = gradleRunner.build(runTask)

        assertThat(cleanResult.task(":kmp-application:clean")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(runResult.output).contains("Hello, world!")
    }

    @Test
    fun `repeated CMake lifecycle tasks are up to date`() {
        val suffix = hostTargetSuffix()
        val generateTask = ":kmp-application:cmakeGenerate$suffix"
        val buildTask = ":kmp-application:cmakeBuild$suffix"
        val installTask = ":kmp-application:cmakeInstall$suffix"

        gradleRunner.build(installTask)
        val repeatResult = gradleRunner.build(installTask)

        assertThat(repeatResult.task(generateTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(repeatResult.task(buildTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(repeatResult.task(installTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `editing C source reruns CMake task and updates application output`() {
        val buildTask = ":kmp-application:cmakeBuild${hostTargetSuffix()}"
        val runTask = ":kmp-application:runReleaseExecutable${hostTargetSuffix()}"

        gradleRunner.build(runTask)
        val source = gradleRunner.root.dir / "cmake/src/hello.c"
        source.replaceText("Hello, world!", "Changed, world!")

        val rebuildResult = gradleRunner.build(buildTask)
        val runResult = gradleRunner.build(runTask)

        assertThat(rebuildResult.task(buildTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(runResult.output).contains("Changed, world!")
    }

    @Test
    fun `installed interop surface contains public headers only`() {
        val project = gradleRunner.root.project("kmp-application")
        gradleRunner.build(":kmp-application:cmakeInstall${hostTargetSuffix()}")

        val installDirectory = project.buildDir / "outputs/cmake/${hostTargetName()}"
        val definition = project.buildDir / "generated/cmake/${hostTargetName()}/cmake.def"
        assertThat(installDirectory / "include/hello.h").exists()
        assertThat(installDirectory / "include/hello_impl.h").doesNotExist()
        val archives = (installDirectory / "lib").toFile().walkTopDown().filter { it.isFile }.toList()
        assertThat(archives).hasSize(1)
        val archiveName = archives.single().name
        assertThat(archiveName.endsWith(".a") || archiveName.endsWith(".lib", ignoreCase = true)).isTrue()
        assertThat(definition)
            .content()
            .contains("hello.h")
            .doesNotContain("hello_impl.h")
    }

    @Test
    fun `install reports every invalid output before failing`() {
        val cmakeLists = gradleRunner.root.dir / "cmake/CMakeLists.txt"
        cmakeLists.replaceText(
            """
            install(TARGETS hello
                ARCHIVE DESTINATION lib COMPONENT third-party-devel
                FILE_SET HEADERS DESTINATION include COMPONENT third-party-devel
            )
            """.trimIndent(),
            "install(FILES src/hello_impl.h DESTINATION unexpected)",
        )

        val result = gradleRunner.build(
            ":kmp-application:cmakeInstall${hostTargetSuffix()}",
            expectFailure = true,
        )

        assertThat(result.output)
            .contains("CMake project did not install public headers to")
            .contains("CMake project did not install a static archive to")
    }

    private fun hostTargetSuffix(): String {
        val host = hostTarget
        assumeTrue(host != null, "No exact-host fixture target is available on this host")
        return host!!.suffix
    }

    private fun hostTargetName(): String {
        val host = hostTarget
        assumeTrue(host != null, "No exact-host fixture target is available on this host")
        return host!!.name
    }

    private data class HostTarget(
        val name: String,
        val suffix: String,
    )

    private companion object {
        val hostTarget: HostTarget? = when {
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) &&
                System.getProperty("os.arch") in setOf("aarch64", "arm64") -> HostTarget("macosArm64", "MacosArm64")

            System.getProperty("os.name").startsWith("Linux", ignoreCase = true) &&
                System.getProperty("os.arch") in setOf("amd64", "x86_64") -> HostTarget("linuxX64", "LinuxX64")

            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
                System.getProperty("os.arch") in setOf("amd64", "x86_64") -> HostTarget("mingwX64", "MingwX64")

            else -> null
        }
    }
}
