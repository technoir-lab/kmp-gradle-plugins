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
    fun `build generates the overlay file for cinterop processing without mappings`() {
        val buildResult = gradleRunner.build(":build")

        assertThat(buildResult.task(":generateVfsOverlayFile")?.outcome).isEqualTo(SUCCESS)
    }
}
