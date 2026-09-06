package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeToolchainGenerator
import io.technoirlab.gradle.asPath
import io.technoirlab.kotlin.native.utils.KonanProperties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.loadConfigurables
import java.nio.charset.StandardCharsets
import kotlin.io.path.createParentDirectories
import kotlin.io.path.writeText

/**
 * Generates a CMake toolchain from a prepared Kotlin/Native distribution.
 */
@DisableCachingByDefault(because = "The generated toolchain contains absolute Kotlin/Native dependency paths")
internal abstract class CMakeGenerateToolchainTask : DefaultTask() {
    @get:Input
    abstract val konanTarget: Property<KonanTarget>

    @get:Internal
    abstract val kotlinNativeDependenciesDirectory: DirectoryProperty

    // Track the location embedded in the toolchain without fingerprinting the entire dependency cache.
    @get:Input
    abstract val kotlinNativeDependenciesDirectoryPath: Property<String>

    @get:Input
    abstract val konanPropertyOverrides: MapProperty<String, String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val konanPropertiesFile: RegularFileProperty

    @get:OutputFile
    abstract val toolchainFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val propertiesFile = konanPropertiesFile.get().asPath()
        val konanTarget = konanTarget.get()
        val konanProperties = KonanProperties.load(propertiesFile, konanPropertyOverrides.get())
        val configurables = loadConfigurables(
            konanTarget,
            konanProperties,
            kotlinNativeDependenciesDirectoryPath.get(),
            progressCallback = { _, _, _ -> },
        )
        val toolchainGenerator = CMakeToolchainGenerator()
        val output = toolchainFile.get().asPath().createParentDirectories()
        output.writeText(toolchainGenerator.generate(configurables), StandardCharsets.UTF_8)
    }
}
