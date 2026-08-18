package io.technoirlab.cmake.import

import io.technoirlab.cmake.import.api.CMakeImportExtension
import io.technoirlab.cmake.import.tasks.CMakeBuildTask
import io.technoirlab.cmake.import.tasks.CMakeGenerateTask
import io.technoirlab.cmake.import.tasks.CMakeGenerateToolchainTask
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
import org.jetbrains.kotlin.gradle.targets.native.internal.KotlinNativeDownloadTask
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.File

/**
 * Gradle plugin that installs a CMake target for Kotlin/Native C-interop.
 */
class CMakeImportPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val extension = extensions.create<CMakeImportExtension>(CMakeImportExtension.NAME).apply {
            buildType.convention(DEFAULT_BUILD_TYPE)
        }

        whenPluginApplied("org.jetbrains.kotlin.multiplatform") {
            extensions.configure<KotlinMultiplatformExtension> {
                targets.withType<KotlinNativeTarget>().configureEach {
                    configureNativeTarget(this, extension)
                }
            }
        }
    }

    private fun Project.configureNativeTarget(target: KotlinNativeTarget, extension: CMakeImportExtension) {
        val kotlinTarget = target.name
        val cmakeBuildDirectory = layout.buildDirectory.dir("intermediates/cmake/$kotlinTarget")
        val cmakeInstallDirectory = layout.buildDirectory.dir("outputs/cmake/$kotlinTarget")
        val taskStateDirectory = layout.buildDirectory.dir("tmp/cmake/$kotlinTarget")
        val targetEnabled = HostManager().isEnabled(target.konanTarget)
        val toolchainTask = registerToolchainTask(target, targetEnabled)
        val generateTask = registerGenerateTask(
            kotlinTarget,
            toolchainTask,
            extension,
            cmakeBuildDirectory,
            taskStateDirectory,
            targetEnabled,
        )
        val buildTask = registerBuildTask(kotlinTarget, generateTask, extension, cmakeBuildDirectory, taskStateDirectory)
        val installTask = registerInstallTask(
            kotlinTarget,
            buildTask,
            extension,
            cmakeBuildDirectory,
            cmakeInstallDirectory,
            cinteropName = CINTEROP_NAME,
        )
        listOf(buildTask, installTask).forEach { task ->
            task.configure { enabled = targetEnabled }
        }

        target.compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME) {
            cinterops.register(CINTEROP_NAME) {
                definitionFile.set(installTask.flatMap { it.definitionFile })
                tasks.named(interopProcessingTaskName).configure {
                    enabled = targetEnabled
                    dependsOn(installTask)
                    inputs.dir(installTask.flatMap { it.installDirectory })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
    }

    private fun Project.registerToolchainTask(
        target: KotlinNativeTarget,
        targetEnabled: Boolean,
    ): TaskProvider<CMakeGenerateToolchainTask> {
        val nativeDistributionTask = tasks.named(
            KOTLIN_NATIVE_DOWNLOAD_TASK_NAME,
            KotlinNativeDownloadTask::class.java,
        )
        val dependenciesDirectory = providers.gradleProperty(KONAN_DATA_DIR_GRADLE_PROPERTY)
            .map { File(it) }
            .orElse(providers.environmentVariable(KONAN_DATA_DIR_ENVIRONMENT_VARIABLE).map { File(it) })
            .orElse(providers.systemProperty(USER_HOME_SYSTEM_PROPERTY).map { File(it, KONAN_HOME_DIRECTORY_NAME) })
            .map { File(it, KOTLIN_NATIVE_DEPENDENCIES_DIRECTORY_NAME).absolutePath }
        return tasks.register<CMakeGenerateToolchainTask>(
            "cmakeGenerateToolchain${target.name.capitalized()}",
        ) {
            konanTargetName.set(target.konanTarget.name)
            kotlinNativeDependenciesDirectory.set(dependenciesDirectory)
            nativeHomeMarker.set(nativeDistributionTask.flatMap { it.nativeDirectoryLocation })
            konanPropertiesFile.set(
                nativeDistributionTask.flatMap {
                    it.konanHome.file(CMakeGenerateToolchainTask.KONAN_PROPERTIES_PATH)
                },
            )
            toolchainFile.set(layout.buildDirectory.file("generated/cmake/${target.name}/toolchain.cmake"))
            enabled = targetEnabled
            if (targetEnabled) {
                dependsOn(nativeDistributionTask)
            }
        }
    }

    private fun Project.registerGenerateTask(
        kotlinTarget: String,
        toolchainTask: TaskProvider<CMakeGenerateToolchainTask>,
        extension: CMakeImportExtension,
        cmakeBuildDirectory: Provider<Directory>,
        taskStateDirectory: Provider<Directory>,
        targetEnabled: Boolean,
    ) = tasks.register<CMakeGenerateTask>("cmakeGenerate${kotlinTarget.capitalized()}") {
        projectDirectory.set(extension.sourceDirectory)
        buildType.set(extension.buildType)
        defines.set(extension.defines)
        toolchainFile.set(toolchainTask.flatMap { it.toolchainFile })
        configureDirectory.set(cmakeBuildDirectory)
        cacheFile.set(cmakeBuildDirectory.map { it.file(CMAKE_CACHE_FILE_NAME) })
        generateOutputDirectory.set(taskStateDirectory.map { it.dir("generate") })
        enabled = targetEnabled
        dependsOn(toolchainTask)
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
        private const val KOTLIN_NATIVE_DOWNLOAD_TASK_NAME = "downloadKotlinNativeDistribution"
        private const val KONAN_DATA_DIR_GRADLE_PROPERTY = "konan.data.dir"
        private const val KONAN_DATA_DIR_ENVIRONMENT_VARIABLE = "KONAN_DATA_DIR"
        private const val USER_HOME_SYSTEM_PROPERTY = "user.home"
        private const val KONAN_HOME_DIRECTORY_NAME = ".konan"
        private const val KOTLIN_NATIVE_DEPENDENCIES_DIRECTORY_NAME = "dependencies"

        @Suppress("UnstableApiUsage")
        internal val PROBLEM_GROUP = ProblemGroup.create("cmake-import", "CMake Import plugin")
    }
}
