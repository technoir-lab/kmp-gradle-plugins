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
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.writeText

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

    @Test
    fun `reruns cinterop processing when overlay mappings or mapped headers change`() {
        val sdk1 = (gradleRunner.root.dir / "sdk-1.0-headers").createDirectories()
        val sdk2 = (gradleRunner.root.dir / "sdk-2.0-headers").createDirectories()
        (sdk1 / "sample.h").writeText("int sample_v1(void);\n")
        (sdk2 / "sample.h").writeText("int sample_v2(void);\n")
        val defFile = gradleRunner.root.dir / "src/nativeInterop/cinterop/sample.def"
        defFile.parent.createDirectories()
        val virtualDir = (gradleRunner.root.dir / "bundled-headers").toAbsolutePath().toString().replace("\\", "/")
        defFile.writeText(
            """
            package = com.example.kmp
            headers = sample.h
            compilerOpts = -I$virtualDir
            """.trimIndent(),
        )
        gradleRunner.root.appendBuildScript(
            """
            vfsOverlay {
                mapping(layout.projectDirectory.dir("bundled-headers"), layout.projectDirectory.dir("sdk-1.0-headers"))
            }
            """.trimIndent(),
        )

        val firstRun = gradleRunner.build(":cinteropSampleAndroidNativeArm64")
        assertThat(firstRun.task(":cinteropSampleAndroidNativeArm64")?.outcome).isEqualTo(SUCCESS)

        gradleRunner.root.buildScript.replaceText("sdk-1.0-headers", "sdk-2.0-headers")
        val afterMappingChange = gradleRunner.build(":cinteropSampleAndroidNativeArm64")
        assertThat(afterMappingChange.task(":generateVfsOverlayFile")?.outcome).isEqualTo(SUCCESS)
        assertThat(afterMappingChange.task(":cinteropSampleAndroidNativeArm64")?.outcome).isEqualTo(SUCCESS)

        (sdk2 / "sample.h").writeText("int sample_v3(void);\n")
        val afterHeaderChange = gradleRunner.build(":cinteropSampleAndroidNativeArm64")
        assertThat(afterHeaderChange.task(":cinteropSampleAndroidNativeArm64")?.outcome).isEqualTo(SUCCESS)
    }
}
