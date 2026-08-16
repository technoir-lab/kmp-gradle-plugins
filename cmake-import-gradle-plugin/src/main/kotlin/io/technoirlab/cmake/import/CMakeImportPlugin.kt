package io.technoirlab.cmake.import

import io.technoirlab.cmake.import.api.CMakeImportExtension
import io.technoirlab.cmake.import.tasks.CMakeBuildTask
import io.technoirlab.cmake.import.tasks.CMakeGenerateTask
import io.technoirlab.cmake.import.tasks.CMakeInstallTask
import io.technoirlab.core.capitalized
import io.technoirlab.gradle.whenPluginApplied
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager

/**
 * Gradle plugin that installs a host-native CMake target for Kotlin/Native C-interop.
 */
class CMakeImportPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val extension = extensions.create<CMakeImportExtension>(CMakeImportExtension.NAME).apply {
            buildType.convention(DEFAULT_BUILD_TYPE)
        }

        whenPluginApplied("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                targets.withType<KotlinNativeTarget>().configureEach {
                    if (HostManager.host == konanTarget) {
                        configureNativeTarget(this, extension)
                    }
                }
            }
        }
    }

    private fun Project.configureNativeTarget(target: KotlinNativeTarget, extension: CMakeImportExtension) {
        val kotlinTarget = target.name
        val cmakeBuildDirectory = layout.buildDirectory.dir("intermediates/cmake/$kotlinTarget")
        val cmakeInstallDirectory = layout.buildDirectory.dir("outputs/cmake/$kotlinTarget")
        val taskStateDirectory = layout.buildDirectory.dir("tmp/cmake/$kotlinTarget")
        val generateTask = registerGenerateTask(kotlinTarget, extension, cmakeBuildDirectory, taskStateDirectory)
        val buildTask = registerBuildTask(kotlinTarget, generateTask, extension, cmakeBuildDirectory, taskStateDirectory)
        val installTask = registerInstallTask(
            kotlinTarget,
            buildTask,
            extension,
            cmakeBuildDirectory,
            cmakeInstallDirectory,
            cinteropName = CINTEROP_NAME,
        )

        target.compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME) {
            cinterops.register(CINTEROP_NAME) {
                definitionFile.set(installTask.flatMap { it.definitionFile })
                tasks.named(interopProcessingTaskName).configure {
                    inputs.dir(installTask.flatMap { it.installDirectory })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
    }

    private fun Project.registerGenerateTask(
        kotlinTarget: String,
        extension: CMakeImportExtension,
        cmakeBuildDirectory: Provider<Directory>,
        taskStateDirectory: Provider<Directory>,
    ) = tasks.register<CMakeGenerateTask>("cmakeGenerate${kotlinTarget.capitalized()}") {
        projectDirectory.set(extension.sourceDirectory)
        buildType.set(extension.buildType)
        defines.set(extension.defines)
        configureDirectory.set(cmakeBuildDirectory)
        cacheFile.set(cmakeBuildDirectory.map { it.file(CMAKE_CACHE_FILE_NAME) })
        generateOutputDirectory.set(taskStateDirectory.map { it.dir("generate") })
    }

    private fun Project.registerBuildTask(
        kotlinTarget: String,
        generateTask: TaskProvider<CMakeGenerateTask>,
        extension: CMakeImportExtension,
        cmakeBuildDirectory: Provider<Directory>,
        taskStateDirectory: Provider<Directory>,
    ) = tasks.register<CMakeBuildTask>("cmakeBuild${kotlinTarget.capitalized()}") {
        projectDirectory.set(extension.sourceDirectory)
        targetName.set(extension.targetName)
        buildType.set(extension.buildType)
        generateOutputDirectory.set(generateTask.flatMap { it.generateOutputDirectory })
        configureDirectory.set(cmakeBuildDirectory)
        buildOutputDirectory.set(taskStateDirectory.map { it.dir("build") })
        dependsOn(generateTask)
    }

    private fun Project.registerInstallTask(
        kotlinTarget: String,
        buildTask: TaskProvider<CMakeBuildTask>,
        extension: CMakeImportExtension,
        cmakeBuildDirectory: Provider<Directory>,
        cmakeInstallDirectory: Provider<Directory>,
        cinteropName: String,
    ) = tasks.register<CMakeInstallTask>("cmakeInstall${kotlinTarget.capitalized()}") {
        projectDirectory.set(extension.sourceDirectory)
        targetName.set(extension.targetName)
        packageName.set(extension.packageName)
        buildType.set(extension.buildType)
        installComponent.set(extension.installComponent)
        configureDirectory.set(cmakeBuildDirectory)
        installDirectory.set(cmakeInstallDirectory)
        definitionFile.set(layout.buildDirectory.file("generated/cmake/$kotlinTarget/$cinteropName.def"))
        dependsOn(buildTask)
    }

    internal companion object {
        private const val DEFAULT_BUILD_TYPE = "Release"
        private const val CMAKE_CACHE_FILE_NAME = "CMakeCache.txt"
        private const val CINTEROP_NAME = "cmake"

        @Suppress("UnstableApiUsage")
        internal val PROBLEM_GROUP = ProblemGroup.create("cmake-import", "CMake Import plugin")
    }
}
