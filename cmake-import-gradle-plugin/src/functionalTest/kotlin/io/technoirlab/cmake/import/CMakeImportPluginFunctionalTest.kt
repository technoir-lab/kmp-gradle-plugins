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
import kotlin.io.path.appendText
import kotlin.io.path.div
import kotlin.io.path.writeText

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
                installComponent = "hello-static"
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
    fun `generated target toolchain overrides a configured CMake toolchain definition`() {
        gradleRunner.root.project("kmp-application").appendBuildScript(
            """
            cmakeImport {
                defines.put("CMAKE_TOOLCHAIN_FILE", "missing-user-toolchain.cmake")
            }
            """.trimIndent(),
        )

        gradleRunner.build(":kmp-application:cmakeGenerate${hostTargetSuffix()}")

        val project = gradleRunner.root.project("kmp-application")
        val toolchain = project.buildDir / "generated/cmake/${hostTargetName()}/toolchain.cmake"
        val cache = project.buildDir / "intermediates/cmake/${hostTargetName()}/CMakeCache.txt"
        assertThat(cache)
            .content()
            .contains("CMAKE_TOOLCHAIN_FILE:FILEPATH=${toolchain.toAbsolutePath().cmakePath()}")
            .doesNotContain("missing-user-toolchain.cmake")
    }

    @Test
    fun `changing the generated toolchain recreates CMake state`() {
        val suffix = hostTargetSuffix()
        val generateTask = ":kmp-application:cmakeGenerate$suffix"
        val toolchainTask = ":kmp-application:cmakeGenerateToolchain$suffix"
        val project = gradleRunner.root.project("kmp-application")

        gradleRunner.build(generateTask)

        val configureDirectory = project.buildDir / "intermediates/cmake/${hostTargetName()}"
        val staleState = configureDirectory / "stale-state"
        staleState.writeText("must be removed")
        val toolchain = project.buildDir / "generated/cmake/${hostTargetName()}/toolchain.cmake"
        toolchain.appendText("\n# Simulate a changed toolchain from a plugin or Kotlin upgrade.\n")

        val result = gradleRunner.build(generateTask, "-x", toolchainTask)

        assertThat(result.task(generateTask)?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(staleState).doesNotExist()
    }

    @Test
    fun `konan data Gradle property takes precedence over the environment`() {
        val suffix = hostTargetSuffix()
        val propertyDirectory = gradleRunner.root.dir / "property-konan-data"
        val environmentDirectory = gradleRunner.root.dir / "environment-konan-data"
        gradleRunner.root.project("kmp-application").appendBuildScript(
            """
            tasks.register("printCmakeNativeDependenciesDirectory") {
                doLast {
                    val toolchainTask = tasks.named("cmakeGenerateToolchain$suffix").get()
                    println("nativeDependencies=" + toolchainTask.inputs.properties["kotlinNativeDependenciesDirectory"])
                }
            }
            """.trimIndent(),
        )

        val result = gradleRunner.build(
            ":kmp-application:printCmakeNativeDependenciesDirectory",
            configuration = {
                configurationCache = false
                isolatedProjects = false
                gradleProperties["konan.data.dir"] = propertyDirectory
                environmentVariables.putAll(System.getenv())
                environmentVariables["KONAN_DATA_DIR"] = environmentDirectory
            },
        )

        assertThat(result.output).contains("nativeDependencies=${propertyDirectory / "dependencies"}")
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
        val toolchainTask = ":kmp-application:cmakeGenerateToolchain$suffix"
        val generateTask = ":kmp-application:cmakeGenerate$suffix"
        val buildTask = ":kmp-application:cmakeBuild$suffix"
        val installTask = ":kmp-application:cmakeInstall$suffix"

        gradleRunner.build(installTask)
        val repeatResult = gradleRunner.build(installTask)

        assertThat(repeatResult.task(toolchainTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(repeatResult.task(generateTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(repeatResult.task(buildTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
        assertThat(repeatResult.task(installTask)?.outcome).isEqualTo(TaskOutcome.UP_TO_DATE)
    }

    @Test
    fun `builds and links an enabled non-host target with its generated toolchain`() {
        val target = nonHostTarget()
        val project = gradleRunner.root.project("kmp-application")
        val buildDirectory = gradleRunner.root.dir / "b"
        project.appendBuildScript(
            """
            // Keep cross-linker inputs below the Windows MAX_PATH limit in the deeply nested TestKit project.
            layout.buildDirectory = layout.projectDirectory.dir("../b")
            """.trimIndent(),
        )

        val result = gradleRunner.build(":kmp-application:linkReleaseExecutable${target.suffix}")

        assertThat(result.task(":kmp-application:cmakeGenerateToolchain${target.suffix}")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":kmp-application:cmakeInstall${target.suffix}")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":kmp-application:cinteropCmake${target.suffix}")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)
        assertThat(result.task(":kmp-application:linkReleaseExecutable${target.suffix}")?.outcome)
            .isEqualTo(TaskOutcome.SUCCESS)

        val archives = (buildDirectory / "outputs/cmake/${target.name}/lib")
            .toFile()
            .walkTopDown()
            .filter { it.isFile && (it.extension == "a" || it.extension.equals("lib", ignoreCase = true)) }
            .toList()
        assertThat(archives).hasSize(1)

        val toolchain = buildDirectory / "generated/cmake/${target.name}/toolchain.cmake"
        val cache = buildDirectory / "intermediates/cmake/${target.name}/CMakeCache.txt"
        assertThat(toolchain).isRegularFile()
        assertThat(cache)
            .content()
            .contains("CMAKE_TOOLCHAIN_FILE:FILEPATH=${toolchain.toAbsolutePath().cmakePath()}")
    }

    @Test
    fun `unsupported target and CMake tasks are skipped`() {
        assumeTrue(
            System.getProperty("os.name").startsWith("Linux", ignoreCase = true),
            "The fixture's macOS target is unsupported only on Linux and Windows",
        )
        val suffix = "MacosArm64"
        val taskNames = listOf(
            "cmakeGenerateToolchain$suffix",
            "cmakeGenerate$suffix",
            "cmakeBuild$suffix",
            "cmakeInstall$suffix",
            "cinteropCmake$suffix",
        )

        val result = gradleRunner.build(*taskNames.map { ":kmp-application:$it" }.toTypedArray())

        taskNames.forEach { taskName ->
            assertThat(result.task(":kmp-application:$taskName")?.outcome).isEqualTo(TaskOutcome.SKIPPED)
        }
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
    fun `generated definition contains header files only`() {
        val project = gradleRunner.root.project("kmp-application")
        gradleRunner.build(":kmp-application:cmakeInstall${hostTargetSuffix()}")

        val installDirectory = project.buildDir / "outputs/cmake/${hostTargetName()}"
        val definition = project.buildDir / "generated/cmake/${hostTargetName()}/cmake.def"
        assertThat(installDirectory / "include/hello.h").exists()
        assertThat(installDirectory / "include/nested/world.h").exists()
        assertThat(installDirectory / "include/hello.c").exists()
        assertThat(installDirectory / "include/hello_impl.h").doesNotExist()
        val archives = (installDirectory / "lib")
            .toFile()
            .walkTopDown()
            .filter { it.isFile && (it.extension == "a" || it.extension.equals("lib", ignoreCase = true)) }
            .toList()
        assertThat(archives).hasSize(1)
        val archiveName = archives.single().name
        assertThat(archiveName.endsWith(".a") || archiveName.endsWith(".lib", ignoreCase = true)).isTrue()
        val headerEntries = definition.toFile().readLines()
            .single { it.startsWith("headers = ") }
            .removePrefix("headers = ")
            .split(' ')
        assertThat(headerEntries).containsExactly("hello.h", "nested/world.h")
        assertThat(definition)
            .content()
            .contains("\nheaderFilter = hello.h nested/world.h\n")
            .contains("linkerOpts = -L/configured/prefix/lib -lm")
            .doesNotContain("hello.c")
            .doesNotContain("hello_impl.h")
    }

    @Test
    fun `generated definition contains direct linker options`() {
        val project = gradleRunner.root.project("kmp-application")
        val pkgConfigFile = gradleRunner.root.dir / "cmake/hello.pc"
        pkgConfigFile.replaceText(
            "-lm",
            "-Wl,-framework,CoreMedia -Wl,-weak_framework,UniformTypeIdentifiers -pthread -lm",
        )

        gradleRunner.build(":kmp-application:cmakeInstall${hostTargetSuffix()}")

        val definition = project.buildDir / "generated/cmake/${hostTargetName()}/cmake.def"
        assertThat(definition)
            .content()
            .contains(
                "-framework CoreMedia -weak_framework UniformTypeIdentifiers -lpthread -lm",
            )
    }

    @Test
    fun `configured nested header excludes other installed headers`() {
        val project = gradleRunner.root.project("kmp-application")
        project.appendBuildScript(
            """
            cmakeImport {
                headers.add("nested/world.h")
            }
            """.trimIndent(),
        )

        gradleRunner.build(":kmp-application:cmakeInstall${hostTargetSuffix()}")

        val definition = project.buildDir / "generated/cmake/${hostTargetName()}/cmake.def"
        assertThat(definition)
            .content()
            .contains("\nheaders = nested/world.h\n")
            .contains("\nheaderFilter = hello.h nested/world.h\n")
    }

    @Test
    fun `configured headers retain exact path form and are sorted`() {
        val project = gradleRunner.root.project("kmp-application")
        project.appendBuildScript(
            """
            cmakeImport {
                headers.add("nested/world.h")
                headers.add("hello.h")
            }
            """.trimIndent(),
        )

        gradleRunner.build(":kmp-application:cmakeInstall${hostTargetSuffix()}")

        val definition = project.buildDir / "generated/cmake/${hostTargetName()}/cmake.def"
        assertThat(definition)
            .content()
            .contains("\nheaders = hello.h nested/world.h\n")
    }

    @Test
    fun `install reports configured public headers missing from the selected component`() {
        gradleRunner.root.project("kmp-application").appendBuildScript(
            """
            cmakeImport {
                installComponent = "hello-static"
                headers.add("nested/missing.h")
            }
            """.trimIndent(),
        )

        val result = gradleRunner.build(
            ":kmp-application:cmakeInstall${hostTargetSuffix()}",
            expectFailure = true,
        )

        assertThat(result.output)
            .contains("CMake component 'hello-static' did not install configured public header(s) nested/missing.h")
            .contains("at the expected include-relative path(s)")
            .contains("installation include directory")
    }

    @Test
    fun `install reports every invalid output before failing`() {
        val cmakeLists = gradleRunner.root.dir / "cmake/CMakeLists.txt"
        cmakeLists.replaceText(
            """
            install(TARGETS hello
                ARCHIVE DESTINATION lib COMPONENT hello-static
                FILE_SET HEADERS DESTINATION include COMPONENT hello-static
            )
            """.trimIndent(),
            "install(FILES src/hello_impl.h DESTINATION unexpected)",
        )
        cmakeLists.replaceText(
            "install(FILES hello.pc DESTINATION lib/pkgconfig COMPONENT hello-static)",
            "",
        )

        val result = gradleRunner.build(
            ":kmp-application:cmakeInstall${hostTargetSuffix()}",
            expectFailure = true,
        )

        assertThat(result.output)
            .contains("CMake project installed no public headers to")
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

    private fun nonHostTarget(): HostTarget = when {
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> HostTarget("mingwX64", "MingwX64")
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> HostTarget("linuxX64", "LinuxX64")
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> HostTarget("mingwX64", "MingwX64")
        else -> error("No enabled non-host fixture target is available")
    }

    private fun java.nio.file.Path.cmakePath(): String = toString().replace('\\', '/')

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
