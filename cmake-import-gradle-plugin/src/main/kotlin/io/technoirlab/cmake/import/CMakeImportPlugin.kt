package io.technoirlab.cmake.import

import io.technoirlab.cmake.import.api.CMakeImportExtension
import io.technoirlab.cmake.import.internal.KonanProperties
import io.technoirlab.cmake.import.tasks.CMakeBuildTask
import io.technoirlab.cmake.import.tasks.CMakeGenerateTask
import io.technoirlab.cmake.import.tasks.CMakeGenerateToolchainTask
import io.technoirlab.cmake.import.tasks.CMakeInstallTask
import io.technoirlab.core.capitalized
import io.technoirlab.gradle.setDisallowChanges
import io.technoirlab.gradle.whenPluginApplied
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.problems.ProblemGroup
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.internal.KotlinNativeDownloadTask
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
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
        val cmakeBuildDirectory = layout.buildDirectory.dir("intermediates/cmake/${target.name}")
        val cmakeInstallDirectory = layout.buildDirectory.dir("outputs/cmake/${target.name}")
        val taskStateDirectory = layout.buildDirectory.dir("tmp/cmake/${target.name}")
        val toolchainTask = registerGenerateToolchainTask(target, extension)
        val generateTask = registerGenerateTask(
            target,
            toolchainTask,
            extension,
            cmakeBuildDirectory,
            taskStateDirectory,
        )
        val buildTask = registerBuildTask(target, generateTask, extension, cmakeBuildDirectory, taskStateDirectory)
        val installTask = registerInstallTask(
            target,
            buildTask,
            extension,
            cmakeBuildDirectory,
            cmakeInstallDirectory,
            cinteropName = CINTEROP_NAME,
        )

        target.compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME) {
            cinterops.register(CINTEROP_NAME) {
                tasks.named<CInteropProcess>(interopProcessingTaskName).configure {
                    settings.packageName = extension.packageName.get()
                    if (enabled) {
                        definitionFile.set(installTask.flatMap { it.definitionFile })
                        inputs.dir(installTask.flatMap { it.installDirectory })
                            .withPathSensitivity(PathSensitivity.RELATIVE)
                    }
                }
            }
        }
    }

    private fun Project.registerGenerateToolchainTask(
        target: KotlinNativeTarget,
        extension: CMakeImportExtension,
    ): TaskProvider<CMakeGenerateToolchainTask> {
        val nativeDistributionTask = tasks.named<KotlinNativeDownloadTask>(KOTLIN_NATIVE_DOWNLOAD_TASK_NAME)
        return tasks.register<CMakeGenerateToolchainTask>("cmakeGenerateToolchain${target.name.capitalized()}") {
            konanTarget.setDisallowChanges(target.konanTarget)
            kotlinNativeDependenciesDirectory.set(getKotlinNativeDependenciesFolder(providers))
            konanPropertyOverrides.set(getKonanPropertyOverrides(target, extension.buildType))
            nativeHomeMarker.set(nativeDistributionTask.flatMap { it.nativeDirectoryLocation })
            konanPropertiesFile.set(
                nativeDistributionTask.flatMap {
                    it.konanHome.file(KonanProperties.KONAN_PROPERTIES_PATH)
                },
            )
            toolchainFile.set(layout.buildDirectory.file("generated/cmake/${target.name}/toolchain.cmake"))
            dependsOn(nativeDistributionTask)
        }
    }

    private fun Project.registerGenerateTask(
        target: KotlinNativeTarget,
        generateToolchainTask: TaskProvider<CMakeGenerateToolchainTask>,
        extension: CMakeImportExtension,
        cmakeBuildDirectory: Provider<Directory>,
        taskStateDirectory: Provider<Directory>,
    ) = tasks.register<CMakeGenerateTask>("cmakeGenerate${target.name.capitalized()}") {
        konanTarget.setDisallowChanges(target.konanTarget)
        cmakeGenerator.convention(providers.getCMakeGenerator())
        cmakeBuildType.set(extension.buildType)
        cmakeDefines.set(extension.defines)
        sourceDirectory.set(extension.sourceDirectory)
        toolchainFile.set(generateToolchainTask.flatMap { it.toolchainFile })
        configureDirectory.set(cmakeBuildDirectory)
        cacheFile.set(cmakeBuildDirectory.map { it.file(CMAKE_CACHE_FILE_NAME) })
        generateOutputDirectory.set(taskStateDirectory.map { it.dir("generate") })
        dependsOn(generateToolchainTask)
    }

    private fun Project.registerBuildTask(
        target: KotlinNativeTarget,
        generateTask: TaskProvider<CMakeGenerateTask>,
        extension: CMakeImportExtension,
        cmakeBuildDirectory: Provider<Directory>,
        taskStateDirectory: Provider<Directory>,
    ) = tasks.register<CMakeBuildTask>("cmakeBuild${target.name.capitalized()}") {
        konanTarget.setDisallowChanges(target.konanTarget)
        cmakeTarget.set(extension.targetName)
        cmakeBuildType.set(extension.buildType)
        sourceDirectory.set(extension.sourceDirectory)
        generateOutputDirectory.set(generateTask.flatMap { it.generateOutputDirectory })
        configureDirectory.set(cmakeBuildDirectory)
        buildOutputDirectory.set(taskStateDirectory.map { it.dir("build") })
        dependsOn(generateTask)
    }

    private fun Project.registerInstallTask(
        target: KotlinNativeTarget,
        buildTask: TaskProvider<CMakeBuildTask>,
        extension: CMakeImportExtension,
        cmakeBuildDirectory: Provider<Directory>,
        cmakeInstallDirectory: Provider<Directory>,
        cinteropName: String,
    ) = tasks.register<CMakeInstallTask>("cmakeInstall${target.name.capitalized()}") {
        konanTarget.setDisallowChanges(target.konanTarget)
        cmakeTarget.set(extension.targetName)
        cmakeBuildType.set(extension.buildType)
        cmakeComponent.set(extension.installComponent)
        packageName.set(extension.packageName)
        includedHeaders.set(extension.headers)
        sourceDirectory.set(extension.sourceDirectory)
        configureDirectory.set(cmakeBuildDirectory)
        installDirectory.set(cmakeInstallDirectory)
        definitionFile.set(layout.buildDirectory.file("generated/cmake/${target.name}/$cinteropName.def"))
        dependsOn(buildTask)
    }

    private fun Project.getKonanPropertyOverrides(
        target: KotlinNativeTarget,
        cmakeBuildType: Provider<String>,
    ): Provider<Map<String, String>> {
        val compilationKonanPropertyOverrides = target.compilations.named(KotlinCompilation.MAIN_COMPILATION_NAME)
            .flatMap { compilation ->
                compilation.compileTaskProvider.flatMap { compileTask ->
                    compileTask.compilerOptions.freeCompilerArgs.map(KonanProperties::parse)
                }
            }
        // Link tasks expose KGP's effective common/target/compilation/binary arguments in precedence order.
        // Keep build types separate because CMake generates one toolchain for the configured build type.
        val debugBinaryKonanPropertyOverrides = objects.listProperty<Map<String, String>>()
        val releaseBinaryKonanPropertyOverrides = objects.listProperty<Map<String, String>>()
        target.binaries.configureEach {
            if (compilation.name == KotlinCompilation.MAIN_COMPILATION_NAME) {
                val overrides = linkTaskProvider.flatMap { linkTask ->
                    linkTask.toolOptions.freeCompilerArgs.map(KonanProperties::parse)
                }
                when (buildType) {
                    NativeBuildType.DEBUG -> debugBinaryKonanPropertyOverrides.add(overrides)
                    NativeBuildType.RELEASE -> releaseBinaryKonanPropertyOverrides.add(overrides)
                }
            }
        }
        val binaryKonanPropertyOverrides = cmakeBuildType.flatMap { buildType ->
            if (buildType.equals(NativeBuildType.DEBUG.name, ignoreCase = true)) {
                debugBinaryKonanPropertyOverrides
            } else {
                releaseBinaryKonanPropertyOverrides
            }
        }
        return compilationKonanPropertyOverrides.zip(
            binaryKonanPropertyOverrides,
        ) { compilationOverrides, binaryOverrides ->
            KonanProperties.select(compilationOverrides, binaryOverrides)
        }
    }

    private fun getKotlinNativeDependenciesFolder(providers: ProviderFactory): Provider<String> =
        providers.gradleProperty(KONAN_DATA_DIR_GRADLE_PROPERTY)
            .map { File(it) }
            .orElse(providers.environmentVariable(KONAN_DATA_DIR_ENVIRONMENT_VARIABLE).map { File(it) })
            .orElse(providers.systemProperty(USER_HOME_SYSTEM_PROPERTY).map { File(it, KONAN_HOME_DIRECTORY_NAME) })
            .map { File(it, KOTLIN_NATIVE_DEPENDENCIES_DIRECTORY_NAME).absolutePath }

    @Suppress("UnstableApiUsage")
    private fun ProviderFactory.getCMakeGenerator(): Provider<String> = environmentVariable("CMAKE_GENERATOR")
        .filter { it.isNotBlank() }
        .orElse(getDefaultCMakeGenerator())

    private fun ProviderFactory.getDefaultCMakeGenerator(): Provider<String> = systemProperty("os.name").map { os ->
        when {
            os.startsWith("Windows", ignoreCase = true) -> "Ninja"
            else -> null
        }
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
