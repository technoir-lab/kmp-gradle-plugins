package io.technoirlab.openapi.kotlin.generator.api

import org.gradle.api.provider.Property
import java.net.URI

/**
 * Configuration of OpenAPI Kotlin generation.
 */
@OpenApiKotlinGeneratorDsl
interface OpenApiKotlinGeneratorExtension {
    /**
     * The OpenAPI specification URI. Setting this property enables generation.
     * Accepts remote URLs and local file URIs.
     */
    val specUrl: Property<URI>

    /**
     * The base package for generated sources. Defaults to the module's package name.
     */
    val packageName: Property<String>

    /**
     * Whether generated declarations are public. Defaults to `true`.
     */
    val publicApi: Property<Boolean>

    /**
     * @suppress
     */
    companion object {
        const val NAME = "openApiKotlinGenerator"
    }
}
