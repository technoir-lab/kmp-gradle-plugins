package io.technoirlab.vfsoverlay

import io.technoirlab.gradle.test.kit.GradleRunnerExtension
import io.technoirlab.gradle.test.kit.appendBuildScript
import io.technoirlab.gradle.test.kit.buildDir
import io.technoirlab.gradle.test.kit.buildScript
import io.technoirlab.gradle.test.kit.replaceText
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.io.path.div

class VfsOverlayPluginFunctionalTest {
    @RegisterExtension
    private val gradleRunner = GradleRunnerExtension("test-project")

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
    fun `regenerates the overlay file when a mapping changes`() {
        gradleRunner.root.appendBuildScript(
            """
            vfsOverlay {
                mapping(layout.projectDirectory.dir("bundled-headers"), layout.projectDirectory.dir("sdk-1.0-headers"))
            }
            """.trimIndent(),
        )
        gradleRunner.build(":generateVfsOverlayFile")
        gradleRunner.root.buildScript.replaceText("sdk-1.0-headers", "sdk-2.0-headers")

        val buildResult = gradleRunner.build(":generateVfsOverlayFile")

        assertThat(buildResult.task(":generateVfsOverlayFile")?.outcome).isEqualTo(SUCCESS)
        assertThat(gradleRunner.root.buildDir / "vfsoverlay/vfsoverlay.json")
            .content()
            .contains("/sdk-2.0-headers")
            .doesNotContain("/sdk-1.0-headers")
    }

    @Test
    fun `build generates the overlay file for cinterop processing without mappings`() {
        val buildResult = gradleRunner.build(":build")

        assertThat(buildResult.task(":generateVfsOverlayFile")?.outcome).isEqualTo(SUCCESS)
    }
}
