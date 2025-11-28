package io.technoirlab.vfsoverlay

import io.technoirlab.gradle.test.kit.GradleRunnerExtension
import io.technoirlab.gradle.test.kit.appendBuildScript
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome.SUCCESS
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class VfsOverlayPluginFuncionalTest {
    @RegisterExtension
    private val gradleRunner = GradleRunnerExtension("test-project")

    @Test
    fun `has mappings`() {
        gradleRunner.root.appendBuildScript(
            """
            vfsOverlay {
                mapping(
                    source = provider { File("/tmp/source1") },
                    target = provider { File("/tmp/target1") }
                )
                mapping(
                    source = provider { File("/tmp/source2") },
                    target = provider { null }
                )
            }
            """.trimIndent()
        )

        val buildResult = gradleRunner.build(":build")

        assertThat(buildResult.task(":generateVfsOverlayFile")?.outcome).isEqualTo(SUCCESS)
    }

    @Test
    fun `no mappings`() {
        gradleRunner.build(":build")
    }
}
