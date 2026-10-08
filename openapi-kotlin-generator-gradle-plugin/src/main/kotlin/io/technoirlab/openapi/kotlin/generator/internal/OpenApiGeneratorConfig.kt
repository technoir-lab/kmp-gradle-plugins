package io.technoirlab.openapi.kotlin.generator.internal

internal object OpenApiGeneratorConfig {
    const val LIBRARY = "multiplatform"

    val GENERATOR = KotlinClientGenerator::class.java.name
    val CONFIG_OPTIONS = mapOf(
        "dateLibrary" to "kotlinx-datetime",
        "omitGradleWrapper" to "true",
        "enumPropertyNaming" to "UPPERCASE",
        "generateOneOfAnyOfWrappers" to "true",
    )
    val GLOBAL_PROPERTIES = mapOf(
        // Keep all APIs and models enabled when supporting files are constrained.
        "apis" to "",
        "models" to "",
    )
    val NORMALIZER_RULES = mapOf(
        "REPLACE_ONE_OF_BY_DISCRIMINATOR_MAPPING" to "true",
    )
    val SUPPORTING_FILES = listOf(
        // Enable generator metadata without generating supporting source or build files.
        ".openapi-generator/FILES",
        ".openapi-generator/VERSION",
    )
    val TYPE_MAPPINGS = mapOf(
        "UUID" to "Uuid",
        // Keep binary schemas independent of the generator's infrastructure wrappers.
        "file" to "kotlin.ByteArray",
        "binary" to "kotlin.ByteArray",
        "string+byte" to "kotlin.String",
        "AnyType" to "JsonElement",
        "object" to "JsonElement",
    )
    val IMPORT_MAPPINGS = mapOf(
        "JsonElement" to "kotlinx.serialization.json.JsonElement",
        "Uuid" to "kotlin.uuid.Uuid",
    )
}
