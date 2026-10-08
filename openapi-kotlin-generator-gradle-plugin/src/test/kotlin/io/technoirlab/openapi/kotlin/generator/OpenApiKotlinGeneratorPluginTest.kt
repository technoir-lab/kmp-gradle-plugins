package io.technoirlab.openapi.kotlin.generator

import io.technoirlab.gradle.test.kit.createRootProject
import io.technoirlab.gradle.test.kit.evaluate
import io.technoirlab.openapi.kotlin.generator.api.OpenApiKotlinGeneratorExtension
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.named
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import java.net.URI

class OpenApiKotlinGeneratorPluginTest {
    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = createRootProject("openapi-kotlin-generator-fixture")
    }

    @Test
    fun `configures OpenAPI generation in a Kotlin JVM project`() {
        project.apply(plugin = "org.jetbrains.kotlin.jvm")
        project.apply(plugin = "io.technoirlab.openapi-kotlin-generator")
        project.configure<OpenApiKotlinGeneratorExtension> {
            specUrl.set(URI("https://example.com/openapi.yaml"))
            packageName.set("com.example.client")
        }

        project.evaluate()

        val generateTask = project.tasks.named<GenerateTask>("openApiGenerate").get()
        assertThat(project.plugins.hasPlugin("org.openapi.generator")).isTrue()
        assertThat(generateTask.remoteInputSpec.get()).isEqualTo("https://example.com/openapi.yaml")
        assertThat(generateTask.packageName.get()).isEqualTo("com.example.client")
        assertThat(generateTask.configOptions.get()).containsEntry("sourceFolder", "src/main/kotlin")
    }

    @Test
    fun `configures OpenAPI generation in a Kotlin Multiplatform project`() {
        project.apply(plugin = "org.jetbrains.kotlin.multiplatform")
        project.apply(plugin = "io.technoirlab.openapi-kotlin-generator")
        project.configure<OpenApiKotlinGeneratorExtension> {
            specUrl.set(URI("https://example.com/openapi.yaml"))
            packageName.set("com.example.client")
        }

        project.evaluate()

        val generateTask = project.tasks.named<GenerateTask>("openApiGenerate").get()
        assertThat(project.plugins.hasPlugin("org.openapi.generator")).isTrue()
        assertThat(generateTask.remoteInputSpec.get()).isEqualTo("https://example.com/openapi.yaml")
        assertThat(generateTask.packageName.get()).isEqualTo("com.example.client")
        assertThat(generateTask.configOptions.get()).containsEntry("sourceFolder", "src/commonMain/kotlin")
    }
}
