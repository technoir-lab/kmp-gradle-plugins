package io.technoirlab.cmake.import.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.konan.target.KonanTarget

@DisableCachingByDefault(because = "Base task")
abstract class BaseCMakeTask internal constructor() : DefaultTask() {
    @get:Input
    abstract val konanTarget: Property<KonanTarget>
}
