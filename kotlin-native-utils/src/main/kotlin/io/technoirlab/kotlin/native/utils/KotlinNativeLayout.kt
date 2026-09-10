package io.technoirlab.kotlin.native.utils

import org.gradle.api.file.ProjectLayout
import org.gradle.api.provider.ProviderFactory
import java.io.File

/**
 * Resolves Kotlin/Native data and dependency directories relative to the consuming project.
 */
class KotlinNativeLayout(
    providers: ProviderFactory,
    layout: ProjectLayout,
) {
    val dataDirectory = layout.dir(
        providers.gradleProperty(KONAN_DATA_DIR_GRADLE_PROPERTY)
            .orElse(providers.environmentVariable(KONAN_DATA_DIR_ENVIRONMENT_VARIABLE))
            .map { File(it) }
            .orElse(
                providers.systemProperty(USER_HOME_SYSTEM_PROPERTY)
                    .map { File(it, KONAN_HOME_DIRECTORY_NAME) },
            ),
    )

    val dependenciesDirectory = dataDirectory.map { it.dir(DEPENDENCIES_DIRECTORY_NAME) }

    private companion object {
        const val KONAN_DATA_DIR_GRADLE_PROPERTY = "konan.data.dir"
        const val KONAN_DATA_DIR_ENVIRONMENT_VARIABLE = "KONAN_DATA_DIR"
        const val USER_HOME_SYSTEM_PROPERTY = "user.home"
        const val KONAN_HOME_DIRECTORY_NAME = ".konan"
        const val DEPENDENCIES_DIRECTORY_NAME = "dependencies"
    }
}
