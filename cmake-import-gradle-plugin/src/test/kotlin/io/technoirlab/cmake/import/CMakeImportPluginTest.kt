package io.technoirlab.cmake.import

import io.technoirlab.cmake.import.api.CMakeImportExtension
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CMakeImportPluginTest {
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

    private fun Project.applyPlugins() {
        apply(plugin = "org.jetbrains.kotlin.multiplatform")
        apply(plugin = "io.technoirlab.cmake-import")
    }

    private fun Project.linuxToolchainTask(): CMakeGenerateToolchainTask =
        tasks.named<CMakeGenerateToolchainTask>("cmakeGenerateToolchainLinuxX64").get()
}
