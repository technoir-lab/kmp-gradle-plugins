package io.technoirlab.vfsoverlay

import io.technoirlab.gradle.whenPluginApplied
import io.technoirlab.vfsoverlay.api.VfsOverlayExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.Family

class VfsOverlayPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val config = extensions.create(
            name = "vfsOverlay",
            publicType = VfsOverlayExtension::class,
            instanceType = VfsOverlayExtensionImpl::class
        )

        whenPluginApplied("org.jetbrains.kotlin.multiplatform") {
            val overlayFile = layout.buildDirectory.file("vfsoverlay/vfsoverlay.json")
            val generateVfsOverlayFileTask = tasks.register<GenerateVfsOverlayFileTask>("generateVfsOverlayFile") {
                mappings.set(config.mappings)
                this.overlayFile.set(overlayFile)
            }

            configure<KotlinMultiplatformExtension> {
                targets.withType<KotlinNativeTarget>().configureEach {
                    // Clang-only
                    if (konanTarget.family == Family.MINGW) return@configureEach
                    compilations.configureEach {
                        cinterops.configureEach {
                            tasks.named(interopProcessingTaskName) { dependsOn(generateVfsOverlayFileTask) }
                            compilerOpts("-ivfsoverlay", overlayFile.get().asFile.absolutePath)
                        }
                    }
                }
            }
        }
    }
}
