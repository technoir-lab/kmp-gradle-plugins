package io.technoirlab.openapi.kotlin.generator

import io.technoirlab.core.capitalized
import io.technoirlab.gradle.disable
import io.technoirlab.gradle.setDisallowChanges
import io.technoirlab.openapi.kotlin.generator.api.OpenApiKotlinGeneratorExtension
import io.technoirlab.openapi.kotlin.generator.internal.OpenApiGeneratorConfig
import io.technoirlab.openapi.kotlin.generator.internal.OpenApiKotlinGeneratorExtensionImpl
import io.technoirlab.openapi.kotlin.generator.tasks.OpenApiCleanTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.openapitools.generator.gradle.plugin.OpenApiGeneratorPlugin
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask
import org.openapitools.generator.gradle.plugin.tasks.ValidateTask
import kotlin.io.path.toPath

/**
 * OpenAPI Kotlin generator plugin.
 *
 * DSL: [OpenApiKotlinGeneratorExtension]
 */
class OpenApiKotlinGeneratorPlugin : Plugin<Project> {
    override fun apply(project: Project) = with(project) {
        val extension = extensions.create(
            publicType = OpenApiKotlinGeneratorExtension::class,
            name = OpenApiKotlinGeneratorExtension.NAME,
            instanceType = OpenApiKotlinGeneratorExtensionImpl::class,
        ) as OpenApiKotlinGeneratorExtensionImpl
        extension.initDefaults(project)

        pluginManager.apply("org.openapi.generator")

        val sourceSetName = objects.property<String>().convention("main")
        pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            sourceSetName.set("commonMain")
        }

        configureOpenApiGenerator(extension, sourceSetName)
    }

    private fun Project.configureOpenApiGenerator(config: OpenApiKotlinGeneratorExtension, sourceSetName: Provider<String>) {
        val sourcePath = sourceSetName.map { "src/$it/kotlin" }
        val cleanTask = tasks.register<OpenApiCleanTask>("openApiClean") {
            group = OpenApiGeneratorPlugin.pluginGroup
            description = "Removes previously generated Open API Kotlin sources."
            sourceDirectory.setDisallowChanges(layout.projectDirectory.dir(sourcePath))
            manifestFile.setDisallowChanges(layout.projectDirectory.file(".openapi-generator/FILES"))
            manifestBaseDirectory.setDisallowChanges(layout.projectDirectory)
        }

        val generateTask = tasks.named<GenerateTask>("openApiGenerate") {
            dependsOn(cleanTask)
            workerIsolation.setDisallowChanges("process")
            quiet.setDisallowChanges(true)
            generatorName.setDisallowChanges(OpenApiGeneratorConfig.GENERATOR)
            library.setDisallowChanges(OpenApiGeneratorConfig.LIBRARY)
            val specUrl = config.specUrl.get()
            if (specUrl.scheme == "file") {
                inputSpec.setDisallowChanges(specUrl.toPath().toFile())
            } else {
                remoteInputSpec.setDisallowChanges(config.specUrl.map { it.toString() })
            }
            packageName.setDisallowChanges(config.packageName)
            apiPackage.setDisallowChanges(config.packageName.map { "$it.api" })
            modelPackage.setDisallowChanges(config.packageName.map { "$it.model" })
            outputDir.setDisallowChanges(layout.projectDirectory)
            cleanupOutput.setDisallowChanges(false)
            doNotTrackState("Generates into the project's hand-maintained source directory")

            templateResourcePath.setDisallowChanges("openapi-generator-templates")
            generatorClasspath.from(OpenApiGeneratorConfig::class.java.protectionDomain.codeSource.location.toURI())
            globalProperties.setDisallowChanges(OpenApiGeneratorConfig.GLOBAL_PROPERTIES)
            openapiNormalizer.setDisallowChanges(OpenApiGeneratorConfig.NORMALIZER_RULES)
            supportingFilesConstrainedTo.setDisallowChanges(OpenApiGeneratorConfig.SUPPORTING_FILES)
            generateApiTests.setDisallowChanges(false)
            generateModelTests.setDisallowChanges(false)
            generateApiDocumentation.setDisallowChanges(false)
            generateModelDocumentation.setDisallowChanges(false)
            configOptions.setDisallowChanges(
                config.publicApi.zip(sourcePath) { publicApi, sourceFolder ->
                    OpenApiGeneratorConfig.CONFIG_OPTIONS + mapOf(
                        "sourceFolder" to sourceFolder,
                        "nonPublicApi" to (!publicApi).toString(),
                    )
                },
            )
            importMappings.putAll(OpenApiGeneratorConfig.IMPORT_MAPPINGS)
            typeMappings.putAll(OpenApiGeneratorConfig.TYPE_MAPPINGS)
        }

        tasks.named<ValidateTask>("openApiValidate") {
            val specUrl = config.specUrl.get()
            if (specUrl.scheme == "file") {
                inputSpec.setDisallowChanges(specUrl.toPath().toFile())
            } else {
                remoteInputSpec.setDisallowChanges(config.specUrl.map { it.toString() })
            }
        }

        tasks.named("openApiGenerators") { disable() }
        tasks.named("openApiMeta") { disable() }

        pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
            configureKtLintFormatting(generateTask, sourceSetName)
        }
    }

    private fun Project.configureKtLintFormatting(generateTask: TaskProvider<GenerateTask>, sourceSetName: Provider<String>) {
        generateTask.configure {
            finalizedBy("ktlint${sourceSetName.get().capitalized()}SourceSetFormat")
        }
        tasks.named { it == "runKtlintFormatOver${sourceSetName.get().capitalized()}SourceSet" }.configureEach {
            mustRunAfter(generateTask)
        }
    }
}
