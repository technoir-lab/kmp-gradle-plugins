package io.technoirlab.device.run.gradle

import org.gradle.api.Plugin
import org.gradle.api.initialization.Settings
import org.gradle.kotlin.dsl.apply

/**
 * Runs the instrumentation tests of the Android projects of a build on Device Run.
 *
 * The plugin registers the build service that all projects of the build share, and applies [DeviceRunPlugin] to each project
 * that applies an Android plugin.
 */
class DeviceRunSettingsPlugin : Plugin<Settings> {
    override fun apply(settings: Settings) {
        DeviceRunBuildService.registerIfAbsent(settings.gradle)
        @Suppress("UnstableApiUsage")
        settings.gradle.lifecycle.beforeProject {
            pluginManager.withPlugin("com.android.base") {
                apply<DeviceRunPlugin>()
            }
        }
    }
}
