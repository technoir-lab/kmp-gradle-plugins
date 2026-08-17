package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeToolchainGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.konan.properties.loadProperties
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.loadConfigurables
import java.nio.charset.StandardCharsets
import kotlin.io.path.Path
import kotlin.io.path.createParentDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Generates a CMake toolchain from a prepared Kotlin/Native distribution.
 */
@DisableCachingByDefault(because = "The generated toolchain contains absolute Kotlin/Native dependency paths")
internal abstract class CMakeGenerateToolchainTask : DefaultTask() {
    @get:Input
    abstract val konanTargetName: Property<String>

    @get:Input
    abstract val kotlinNativeDependenciesDirectory: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val nativeHomeMarker: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val konanPropertiesFile: RegularFileProperty

    @get:OutputFile
    abstract val toolchainFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val nativeHome = Path(nativeHomeMarker.get().asFile.readText(StandardCharsets.UTF_8).trim())
        val propertiesFile = konanPropertiesFile.get().asFile.toPath()
        check(propertiesFile == nativeHome.resolve(KONAN_PROPERTIES_PATH)) {
            "Kotlin/Native prepared $nativeHome but provided properties from $propertiesFile"
        }
        val properties = loadProperties(propertiesFile.toString())
        val target = KonanTarget.predefinedTargets.getValue(konanTargetName.get())
        val configurables = loadConfigurables(
            target,
            properties,
            kotlinNativeDependenciesDirectory.get(),
            progressCallback = { _, _, _ -> },
        )
        val output = toolchainFile.get().asFile.toPath().createParentDirectories()
        output.writeText(CMakeToolchainGenerator().generate(configurables), StandardCharsets.UTF_8)
    }

    internal companion object {
        const val KONAN_PROPERTIES_PATH = "konan/konan.properties"
    }
}
