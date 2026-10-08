package io.technoirlab.openapi.kotlin.generator.internal

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.openapitools.codegen.CodegenModel
import org.openapitools.codegen.languages.KotlinClientCodegen

internal class KotlinClientGenerator : KotlinClientCodegen() {
    private var originalSchemaNames = emptySet<String>()

    init {
        // Recognize Kotlin built-in types by the names used in generated declarations.
        languageSpecificPrimitives.addAll(languageSpecificPrimitives.map(::unqualifiedType))
    }

    override fun processOpts() {
        super.processOpts()
        // The generator input supplies the document before normalization and inline schema resolution.
        originalSchemaNames = openAPI?.components?.schemas?.keys?.toSet().orEmpty()
    }

    override fun preprocessOpenAPI(openAPI: OpenAPI) {
        super.preprocessOpenAPI(openAPI)
        val schemaNames = openAPI.components?.schemas?.keys.orEmpty().filterNotTo(HashSet()) { it in schemaMapping }
        modelNameMapping.putAll(ModelNameCollisions(::toModelName).resolve(openAPI, schemaNames, originalSchemaNames))
    }

    override fun getSchemaType(schema: Schema<*>?): String = unqualifiedType(super.getSchemaType(schema))

    override fun getTypeDeclaration(schema: Schema<*>?): String = unqualifiedType(super.getTypeDeclaration(schema))

    override fun fromModel(name: String, schema: Schema<*>): CodegenModel? {
        val model = super.fromModel(name, schema) ?: return null
        // Composed schema processing resets allVars and hasEnums when oneOf or anyOf alternatives only add constraints,
        // such as required properties, which drops the properties the schema declares itself.
        if (model.allVars.isEmpty() && model.vars.isNotEmpty()) {
            model.allVars = model.vars
            model.hasEnums = model.vars.any { it.isEnum }
        }
        // Import the types of scalar union alternatives, which upstream only imports for collections.
        val composedSchemas = model.composedSchemas
        (composedSchemas?.oneOf.orEmpty() + composedSchemas?.anyOf.orEmpty()).forEach { addImports(model, it) }
        return model
    }

    override fun toEnumValue(value: String, datatype: String): String {
        // Upstream recognizes numeric and Boolean enum values by their qualified Kotlin types.
        val enumType = when (datatype) {
            "Int", "Long", "Boolean", "Double", "Float" -> "kotlin.$datatype"
            else -> datatype
        }
        return super.toEnumValue(value, enumType)
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

    private fun unqualifiedType(type: String): String = type.replace(QUALIFIED_TYPE) { match ->
        val qualifiedName = match.value
        val packageName = qualifiedName.substringBeforeLast('.')
        val simpleName = qualifiedName.substringAfterLast('.')
        if (packageName == "kotlin" || packageName == "kotlin.collections" || importMapping[simpleName] == qualifiedName) {
            simpleName
        } else {
            qualifiedName
        }
    }

    private companion object {
        private val QUALIFIED_TYPE = Regex("(?<![\\w.])(?:\\w+\\.)+\\w+(?![\\w.])")
    }
}
