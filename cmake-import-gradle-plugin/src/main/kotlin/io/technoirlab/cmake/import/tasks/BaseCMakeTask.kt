package io.technoirlab.cmake.import.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.konan.target.KonanTarget

@DisableCachingByDefault(because = "Base task")
abstract class BaseCMakeTask internal constructor() : DefaultTask() {
    /**
     * Identifies the Kotlin/Native target so consumers can configure additional task inputs per target.
     */
    @get:Internal
    abstract val konanTarget: Property<KonanTarget>
}
