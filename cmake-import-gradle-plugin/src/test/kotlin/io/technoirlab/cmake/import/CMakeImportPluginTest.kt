package io.technoirlab.cmake.import

import io.technoirlab.cmake.import.api.CMakeImportExtension
import io.technoirlab.cmake.import.tasks.CMakeGenerateCInteropDefinitionTask
import io.technoirlab.cmake.import.tasks.CMakeGenerateToolchainTask
import io.technoirlab.gradle.test.kit.createRootProject
import io.technoirlab.gradle.test.kit.evaluate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CMakeImportPluginTest {
    private val host = HostInfo()
    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = createRootProject("cmake-import-fixture") {
            applyPlugins()
        }
    }

    @Test
    fun `toolchain generation uses main compilation Konan property overrides without main binaries`() {
        project.configure<KotlinMultiplatformExtension> {
            linuxX64 {
                compilations.named("main") {
                    compileTaskProvider.configure {
                        compilerOptions.freeCompilerArgs.add("-Xoverride-konan-properties=targetSysRoot.linux_x64=/sysroots/custom")
                    }
                }
            }
        }

        project.evaluate()

        assertThat(project.linuxToolchainTask().konanPropertyOverrides.get())
            .containsExactly(entry("targetSysRoot.linux_x64", "/sysroots/custom"))
    }

    @Test
    fun `toolchain generation uses Konan property overrides of the binary matching the CMake build type`() {
        project.configure<KotlinMultiplatformExtension> {
            linuxX64 {
                binaries.executable()
                binaries.configureEach {
                    val sysroot = if (buildType == NativeBuildType.DEBUG) "/sysroots/debug" else "/sysroots/release"
                    freeCompilerArgs += "-Xoverride-konan-properties=targetSysRoot.linux_x64=$sysroot"
                }
            }
        }
        project.configure<CMakeImportExtension> {
            buildType.set("Debug")
        }

        project.evaluate()

        assertThat(project.linuxToolchainTask().konanPropertyOverrides.get())
            .containsExactly(entry("targetSysRoot.linux_x64", "/sysroots/debug"))
    }

    @Test
    fun `C-interop processing can be created before the package name is configured`() {
        project.configure<KotlinMultiplatformExtension> {
            linuxX64()
        }
        val cinteropTask = project.cinteropTask("LinuxX64")

        project.configure<CMakeImportExtension> {
            packageName.set("com.example.hello")
        }

        val definitionTask = project.cinteropDefinitionTask("LinuxX64")
        assertThat(definitionTask.packageName.get()).isEqualTo("com.example.hello")
        assertThat(cinteropTask.definitionFile.get()).isEqualTo(definitionTask.definitionFile.get())
    }

    @Test
    fun `C-interop processing for a target the host cannot build names the generated definition without generating it`() {
        assumeTrue(!host.isMac, "Mac hosts build every Kotlin/Native target")
        project.configure<KotlinMultiplatformExtension> {
            iosArm64()
        }

        val cinteropTask = project.cinteropTask("IosArm64")

        assertThat(cinteropTask.enabled).isFalse()
        assertThat(cinteropTask.definitionFile.get()).isEqualTo(project.cinteropDefinitionTask("IosArm64").definitionFile.get())
        assertThat(project.files(cinteropTask.definitionFile).buildDependencies.getDependencies(null)).isEmpty()
    }

    private fun Project.applyPlugins() {
        apply(plugin = "org.jetbrains.kotlin.multiplatform")
        apply(plugin = "io.technoirlab.cmake-import")
    }

    private fun Project.linuxToolchainTask(): CMakeGenerateToolchainTask =
        tasks.named<CMakeGenerateToolchainTask>("cmakeGenerateToolchainLinuxX64").get()

    private fun Project.cinteropDefinitionTask(targetSuffix: String): CMakeGenerateCInteropDefinitionTask =
        tasks.named<CMakeGenerateCInteropDefinitionTask>("cmakeGenerateCInteropDefinition$targetSuffix").get()

    private fun Project.cinteropTask(targetSuffix: String): CInteropProcess =
        tasks.named<CInteropProcess>("cinteropCmake$targetSuffix").get()
}
