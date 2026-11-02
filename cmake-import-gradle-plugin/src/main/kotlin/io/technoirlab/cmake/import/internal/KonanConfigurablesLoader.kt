package io.technoirlab.cmake.import.internal

import io.technoirlab.kotlin.native.utils.KonanProperties
import org.jetbrains.kotlin.konan.target.Configurables
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.loadConfigurables
import java.nio.file.Path

/**
 * Loads the Kotlin/Native configuration of a target from a distribution's `konan.properties`.
 */
internal class KonanConfigurablesLoader {
    fun load(
        target: KonanTarget,
        konanPropertiesFile: Path,
        konanPropertyOverrides: Map<String, String>,
        dependenciesDirectory: String,
    ): Configurables = loadConfigurables(
        target,
        KonanProperties.load(konanPropertiesFile, konanPropertyOverrides),
        dependenciesDirectory,
        progressCallback = { _, _, _ -> },
    )
}
