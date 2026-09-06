package io.technoirlab.vfsoverlay

import io.technoirlab.gradle.test.kit.GradleRunnerExtension
import io.technoirlab.gradle.test.kit.appendBuildScript
import io.technoirlab.gradle.test.kit.buildDir
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.io.path.div

class VfsOverlayPluginFunctionalTest {
    @RegisterExtension
    private val gradleRunner = GradleRunnerExtension("test-project")

    @Test
    fun `has mappings`() {
        gradleRunner.root.appendBuildScript(
            """
            vfsOverlay {
                mapping(
                    source = provider { File("/tmp/source1") },
                    target = provider { File("/tmp/target1") },
                )
                mapping(
                    source = provider { File("/tmp/source2") },
                    target = provider { null },
                )
            }
            """.trimIndent(),
        )

        val buildResult = gradleRunner.build(":build")

        assertThat(buildResult.task(":generateVfsOverlayFile")?.outcome).isEqualTo(SUCCESS)
    }

    @Test
    fun `maps a directory from Kotlin Native dependencies`() {
        gradleRunner.root.appendBuildScript(
            """
            vfsOverlay {
                mapping(
                    source = kotlinNativeDependenciesDir.map { it.dir("include").asFile },
                    target = layout.buildDirectory.dir("headers").map { it.asFile },
                )
            }
            """.trimIndent(),
        )

        val buildResult = gradleRunner.build(":generateVfsOverlayFile", configuration = {
            gradleProperties["konan.data.dir"] = gradleRunner.root.dir / "native-data"
        })

        assertThat(buildResult.task(":generateVfsOverlayFile")?.outcome).isEqualTo(SUCCESS)
        assertThat(gradleRunner.root.buildDir / "vfsoverlay/vfsoverlay.json")
            .content()
            .contains("/native-data/dependencies/include")
    }

    @Test
    fun `maps directories directly`() {
        gradleRunner.root.appendBuildScript(
            """
            vfsOverlay {
                mapping(
                    source = layout.projectDirectory.dir("original-headers"),
                    target = layout.projectDirectory.dir("replacement-headers"),
                )
            }
            """.trimIndent(),
        )

        gradleRunner.build(":generateVfsOverlayFile")

        assertThat(gradleRunner.root.buildDir / "vfsoverlay/vfsoverlay.json")
            .content()
            .contains("/original-headers", "/replacement-headers")
    }

    @Test
    fun `ignores mappings with missing directory providers`() {
        gradleRunner.root.appendBuildScript(
            """
            vfsOverlay {
                mapping(
                    source = objects.directoryProperty().asFile,
                    target = layout.buildDirectory.dir("unused-target").map { it.asFile },
                )
                mapping(
                    source = layout.buildDirectory.dir("unused-source").map { it.asFile },
                    target = objects.directoryProperty().asFile,
                )
            }
            """.trimIndent(),
        )

        gradleRunner.build(":generateVfsOverlayFile")

        assertThat(gradleRunner.root.buildDir / "vfsoverlay/vfsoverlay.json")
            .content()
            .contains("\"roots\": []")
            .doesNotContain("unused-source", "unused-target")
    }

    @Test
    fun `no mappings`() {
        gradleRunner.build(":build")
    }
}
