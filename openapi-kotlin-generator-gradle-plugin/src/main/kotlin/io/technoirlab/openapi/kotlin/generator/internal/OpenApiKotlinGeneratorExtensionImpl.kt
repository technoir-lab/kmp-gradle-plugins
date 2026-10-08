package io.technoirlab.openapi.kotlin.generator.internal

import io.technoirlab.conventions.common.api.CommonExtension
import io.technoirlab.openapi.kotlin.generator.api.OpenApiKotlinGeneratorExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType

internal abstract class OpenApiKotlinGeneratorExtensionImpl : OpenApiKotlinGeneratorExtension {
    fun initDefaults(project: Project) {
        publicApi.convention(true)

        project.pluginManager.withPlugin("io.technoirlab.conventions.common") {
            val commonExtension = project.extensions.getByType<CommonExtension>()
            packageName.convention(commonExtension.packageName)
        }
    }
}
