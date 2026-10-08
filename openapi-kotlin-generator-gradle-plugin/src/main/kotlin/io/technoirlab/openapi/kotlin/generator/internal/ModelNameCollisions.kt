package io.technoirlab.openapi.kotlin.generator.internal

import io.swagger.v3.oas.models.OpenAPI

/**
 * Assigns unique Kotlin model names to component schemas whose keys produce the same class name.
 *
 * Inline schema resolution names extracted schemas after their titles and only rejects exact key matches, so a
 * titled inline schema such as `Deployment` can coexist with a `deployment` component. Both map to the same Kotlin
 * class, and one generated file would replace the other. Schemas declared in the original document keep their
 * names; each extracted schema is prefixed with the model name of the component or operation that first references
 * it, followed by a numeric suffix if that name is still taken.
 */
internal class ModelNameCollisions(
    private val modelName: (String) -> String,
) {
    /**
     * Returns replacement model names keyed by schema name.
     *
     * @param schemaNames component schemas that produce model classes.
     * @param originalSchemaNames component schemas declared before inline schema resolution.
     */
    fun resolve(openAPI: OpenAPI, schemaNames: Set<String>, originalSchemaNames: Set<String>): Map<String, String> {
        val collisions = schemaNames.groupBy(modelName).filterValues { it.size > 1 }
        if (collisions.isEmpty()) {
            return emptyMap()
        }
        val owners = referenceOwners(openAPI)
        val usedNames = schemaNames.mapTo(HashSet(), modelName)
        val renames = LinkedHashMap<String, String>()
        collisions.toSortedMap().forEach { (name, schemas) ->
            schemas.sortedWith(compareBy<String> { it !in originalSchemaNames }.thenBy { it })
                .drop(1)
                .forEach { schema ->
                    val owner = owners[schema]?.let { modelName(it.replace(NON_ALPHANUMERIC, "_")) }.orEmpty()
                    val replacement = uniqueName(owner + name, usedNames)
                    usedNames += replacement
                    renames[schema] = replacement
                }
        }
        return renames
    }

    private fun referenceOwners(openAPI: OpenAPI): Map<String, String> {
        val owners = HashMap<String, String>()
        SchemaTraversal { schema, owner ->
            val target = schema.`$ref`?.takeIf { it.startsWith(SCHEMA_REFERENCE_PREFIX) }?.removePrefix(SCHEMA_REFERENCE_PREFIX)
            if (target != null && owner != null && owner != target) {
                owners.putIfAbsent(target, owner)
            }
        }.traverse(openAPI)
        return owners
    }

    private fun uniqueName(name: String, usedNames: Set<String>): String = generateSequence(1) { it + 1 }
        .map { index -> if (index == 1) name else "$name$index" }
        .first { it !in usedNames }

    private companion object {
        private const val SCHEMA_REFERENCE_PREFIX = "#/components/schemas/"
        private val NON_ALPHANUMERIC = Regex("[^A-Za-z0-9]")
    }
}
