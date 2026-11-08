package io.technoirlab.openapi.kotlin.generator

import io.technoirlab.gradle.test.kit.GradleProject
import io.technoirlab.gradle.test.kit.GradleRunnerExtension
import io.technoirlab.gradle.test.kit.buildScript
import io.technoirlab.gradle.test.kit.kotlinFile
import io.technoirlab.gradle.test.kit.replaceText
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.div
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk
import kotlin.io.path.writeText

class OpenApiKotlinGeneratorPluginFunctionalTest {
    @RegisterExtension
    private val gradleRunner = GradleRunnerExtension("openapi-generator-fixture")

    @Test
    fun `generates the checked-in client`() {
        val project = gradleRunner.root.project("jvm-client")
        val checkedInFiles = project.generatedFiles().associateWith { (project.dir / it).readText() }

        gradleRunner.build(":jvm-client:openApiGenerate")
        updateCheckedInClient(project)
        val generatedFiles = project.generatedFiles()

        assertThat(generatedFiles).containsExactlyInAnyOrderElementsOf(checkedInFiles.keys)
        for ((path, content) in checkedInFiles) {
            assertThat(project.dir / path)
                .content(Charsets.UTF_8)
                .isEqualToNormalizingNewlines(content)
        }
    }

    @Test
    fun `checked-in client passes its tests`() {
        val result = gradleRunner.build(":jvm-client:test")

        assertThat(result.task(":jvm-client:test")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `formats the generated client when KtLint is applied`() {
        val project = gradleRunner.root.project("jvm-client")
        project.buildScript.replaceText("plugins {\n", "plugins {\n    id(\"org.jlleitschuh.gradle.ktlint\")\n")

        val result = gradleRunner.build(":jvm-client:openApiGenerate")

        assertThat(result.task(":jvm-client:ktlintMainSourceSetFormat")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    @Test
    fun `regeneration removes stale generated files and preserves handwritten files`() {
        val project = gradleRunner.root.project("jvm-client")
        val handwrittenFile = project.kotlinFile("com.example.petstore.model.PetExtensions")
        val handwrittenContent = "package com.example.petstore.model\n"
        handwrittenFile.writeText(handwrittenContent)
        val specification = gradleRunner.root.dir / "petstore.yaml"
        specification.replaceText("tags: [Pet]", "tags: [Animal]")
        specification.replaceText("schemas/Pet'", "schemas/Animal'")
        specification.replaceText("    Pet:", "    Animal:")

        gradleRunner.build(":jvm-client:openApiGenerate")

        assertThat(project.kotlinFile("com.example.petstore.api.PetApi")).doesNotExist()
        assertThat(project.kotlinFile("com.example.petstore.model.Pet")).doesNotExist()
        assertThat(project.kotlinFile("com.example.petstore.api.AnimalApi"))
            .content(Charsets.UTF_8)
            .containsPattern("""\R\Rclass AnimalApi\(""")
        assertThat(project.kotlinFile("com.example.petstore.model.Animal")).exists()
        assertThat(handwrittenFile).hasContent(handwrittenContent)
    }

    @Test
    fun `generates a compilable multiplatform client in the common source set`() {
        val project = gradleRunner.root.project("kmp-client")

        val generateResult = gradleRunner.build(":kmp-client:openApiGenerate")
        val compileResult = gradleRunner.build(":kmp-client:compileKotlinJvm", ":kmp-client:compileKotlinLinuxX64")

        assertThat(project.kotlinFile("com.example.petstore.api.PetApi", variant = "commonMain")).exists()
        assertThat(project.kotlinFile("com.example.petstore.model.Pet", variant = "commonMain")).exists()
        assertThat(generateResult.task(":kmp-client:ktlintCommonMainSourceSetFormat")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(compileResult.task(":kmp-client:compileKotlinJvm")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(compileResult.task(":kmp-client:compileKotlinLinuxX64")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
    }

    private fun GradleProject.generatedFiles(): List<Path> = GENERATED_DIRS
        .flatMap { (dir / it).walk() }
        .map { it.relativeTo(dir) }

    // Run with UPDATE_CHECKED_IN_CLIENT=true to replace the fixture's client after an intentional generation change.
    @OptIn(ExperimentalPathApi::class)
    private fun updateCheckedInClient(project: GradleProject) {
        if (!System.getenv("UPDATE_CHECKED_IN_CLIENT").toBoolean()) return
        val fixtureDir = Path("src/functionalTest/resources/openapi-generator-fixture/jvm-client")
        for (generatedDir in GENERATED_DIRS) {
            (fixtureDir / generatedDir).deleteRecursively()
            (project.dir / generatedDir).copyToRecursively(fixtureDir / generatedDir, followLinks = false)
        }
    }

    private companion object {
        private val GENERATED_DIRS = listOf(".openapi-generator", "src/main/kotlin")
    }
}
