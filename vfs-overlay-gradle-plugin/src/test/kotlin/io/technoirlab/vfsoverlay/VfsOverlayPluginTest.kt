package io.technoirlab.vfsoverlay

import io.technoirlab.gradle.test.kit.createRootProject
import io.technoirlab.gradle.test.kit.evaluate
import io.technoirlab.vfsoverlay.api.VfsOverlayExtension
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class VfsOverlayPluginTest {
    private lateinit var project: Project
    private lateinit var extension: VfsOverlayExtension

    @BeforeEach
    fun setUp() {
        project = createRootProject("vfs-overlay-fixture") {
            apply(plugin = "org.jetbrains.kotlin.multiplatform")
            apply(plugin = "io.technoirlab.vfs-overlay")
        }
        extension = project.extensions.getByType<VfsOverlayExtension>()
    }

    @Test
    fun `cinterop processing depends on the overlay file and passes it to the compiler`() {
        project.configure<KotlinMultiplatformExtension> {
            androidNativeArm64 {
                compilations.named("main") {
                    cinterops.register("sample")
                }
            }
        }

        project.evaluate()

        val cinteropTask = project.tasks.named<CInteropProcess>("cinteropSampleAndroidNativeArm64").get()
        val overlayFile = generateTask().get().overlayFile.get().asFile
        assertThat(cinteropTask.dependsOn)
            .filteredOn { it is TaskProvider<*> }
            .extracting<String> { (it as TaskProvider<*>).name }
            .contains("generateVfsOverlayFile")
        assertThat(cinteropTask.settings.compilerOpts).containsSubsequence("-ivfsoverlay", overlayFile.absolutePath)
    }

    @Test
    fun `maps files of present providers and ignores mappings with an absent target`() {
        extension.mapping(
            source = project.provider { File("/headers/source1") },
            target = project.provider { File("/headers/target1") },
        )
        extension.mapping(
            source = project.provider { File("/headers/source2") },
            target = project.provider { null },
        )

        val mappings = generateTask().get().mappings.get()

        assertThat(mappings).containsExactly(entry(File("/headers/source1"), File("/headers/target1")))
    }

    @Test
    fun `maps directories`() {
        val source = project.layout.projectDirectory.dir("original-headers")
        val target = project.layout.projectDirectory.dir("replacement-headers")
        extension.mapping(source = source, target = target)

        val mappings = generateTask().get().mappings.get()

        assertThat(mappings).containsExactly(entry(source.asFile, target.asFile))
    }

    @Test
    fun `ignores mappings with missing directory providers`() {
        extension.mapping(
            source = project.objects.directoryProperty().asFile,
            target = project.layout.buildDirectory.dir("unused-target").map { it.asFile },
        )
        extension.mapping(
            source = project.layout.buildDirectory.dir("unused-source").map { it.asFile },
            target = project.objects.directoryProperty().asFile,
        )

        val mappings = generateTask().get().mappings.get()

        assertThat(mappings).isEmpty()
    }

    private fun generateTask(): TaskProvider<GenerateVfsOverlayFileTask> =
        project.tasks.named<GenerateVfsOverlayFileTask>("generateVfsOverlayFile")
}
