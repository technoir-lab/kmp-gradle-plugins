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
}
