package io.technoirlab.openapi.kotlin.generator.internal

import io.swagger.v3.oas.models.media.Schema
import org.openapitools.codegen.CodegenModel
import org.openapitools.codegen.languages.KotlinClientCodegen

internal class KotlinClientGenerator : KotlinClientCodegen() {
    override fun fromModel(name: String, schema: Schema<*>): CodegenModel? {
        val model = super.fromModel(name, schema) ?: return null
        // Composed schema processing resets allVars and hasEnums when oneOf or anyOf alternatives only add constraints,
        // such as required properties, which drops the properties the schema declares itself.
        if (model.allVars.isEmpty() && model.vars.isNotEmpty()) {
            model.allVars = model.vars
            model.hasEnums = model.vars.any { it.isEnum }
        }
        return model
    }

    override fun toEnumVarName(value: String, datatype: String): String {
        val name = super.toEnumVarName(value, datatype)
        // Upstream prefixes names that would start with a digit, such as numeric values, with an underscore. Kotlin
        // naming conventions require a leading letter, and KtLint cannot correct such names.
        return if (name.startsWith('_')) "VALUE$name" else name
    }

    // Upstream extends HashMap for schemas with additionalProperties and ArrayList for array models. Both classes are final
    // outside the JVM, and kotlinx.serialization would neither read nor write the inherited entries.
    override fun addParentContainer(model: CodegenModel, name: String, schema: Schema<*>) = Unit
}
