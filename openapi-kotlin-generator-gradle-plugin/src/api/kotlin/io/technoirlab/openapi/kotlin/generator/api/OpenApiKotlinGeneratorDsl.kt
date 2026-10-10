package io.technoirlab.openapi.kotlin.generator.api

/**
 * Marks OpenAPI Kotlin Generator plugin DSL.
 */
@DslMarker
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
internal annotation class OpenApiKotlinGeneratorDsl
