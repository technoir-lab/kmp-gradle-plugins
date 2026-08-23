package io.technoirlab.cmake.import.tasks

import io.technoirlab.cmake.import.internal.CMakeToolchainGenerator
import io.technoirlab.cmake.import.internal.KonanProperties
import io.technoirlab.gradle.asPath
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
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
    abstract val konanTarget: Property<KonanTarget>

    @get:Input
    abstract val kotlinNativeDependenciesDirectory: Property<String>

    @get:Input
    abstract val konanPropertyOverrides: MapProperty<String, String>

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
        val nativeHome = Path(nativeHomeMarker.get().asPath().readText(StandardCharsets.UTF_8).trim())
        val propertiesFile = konanPropertiesFile.get().asPath()
        check(propertiesFile == nativeHome.resolve(KonanProperties.KONAN_PROPERTIES_PATH)) {
            "Kotlin/Native prepared $nativeHome but provided properties from $propertiesFile"
        }
        val konanTarget = konanTarget.get()
        val konanProperties = KonanProperties.load(propertiesFile, konanPropertyOverrides.get())
        val configurables = loadConfigurables(
            konanTarget,
            konanProperties,
            kotlinNativeDependenciesDirectory.get(),
            progressCallback = { _, _, _ -> },
        )
        val toolchainGenerator = CMakeToolchainGenerator()
        val output = toolchainFile.get().asPath().createParentDirectories()
        output.writeText(toolchainGenerator.generate(configurables), StandardCharsets.UTF_8)
    }
}
