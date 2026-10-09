package io.technoirlab.openapi.kotlin.generator.internal

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.RequestBody
import org.openapitools.codegen.CodegenModel
import org.openapitools.codegen.CodegenParameter
import org.openapitools.codegen.CodegenProperty
import org.openapitools.codegen.languages.KotlinClientCodegen
import org.openapitools.codegen.utils.ModelUtils

internal class KotlinClientGenerator : KotlinClientCodegen() {
    private var originalSchemaNames = emptySet<String>()

    init {
        // Recognize Kotlin built-in types by the names used in generated declarations.
        languageSpecificPrimitives.addAll(languageSpecificPrimitives.map(::unqualifiedType))
        // Upstream also reserves soft and modifier keywords, such as field and operator, identifiers such as it, and the
        // name of its ApiResponse infrastructure class, which is not generated. Kotlin accepts these names wherever the
        // templates emit them, so reserving them only added backticks and renamed operations and models, such as import
        // to callImport.
        reservedWords = HARD_KEYWORDS.toMutableSet()
    }

    override fun processOpts() {
        super.processOpts()
        // The generator input supplies the document before normalization and inline schema resolution.
        originalSchemaNames = openAPI?.components?.schemas?.keys?.toSet().orEmpty()
    }

    override fun preprocessOpenAPI(openAPI: OpenAPI) {
        super.preprocessOpenAPI(openAPI)
        SchemaTraversal { schema, _ -> widenUnformattedInteger(schema) }.traverse(openAPI)
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
        // The union template imports JsonElement for its serializer, and Kotlin rejects a second import of the same name.
        if (model.oneOf.isNotEmpty() || model.anyOf.isNotEmpty()) {
            model.imports.remove(JSON_ELEMENT_TYPE)
        }
        return model
    }

    override fun fromProperty(
        name: String,
        schema: Schema<*>?,
        required: Boolean,
        schemaIsFromAdditionalProperties: Boolean,
    ): CodegenProperty? {
        val property = super.fromProperty(name, schema, required, schemaIsFromAdditionalProperties) ?: return null
        // Templates declare and reference nested enum classes by this name.
        property.nameInPascalCase = unescapedTypeName(property.nameInPascalCase)
        return property
    }

    // Upstream returns the property's Pascal case name, which also forms datatypeWithEnum, enumName, and enum defaults,
    // including those of enum parameters.
    override fun toEnumName(property: CodegenProperty): String = unescapedTypeName(super.toEnumName(property))

    override fun fromRequestBody(body: RequestBody, imports: MutableSet<String>, bodyParameterName: String?): CodegenParameter? {
        val parameter = super.fromRequestBody(body, imports, bodyParameterName) ?: return null
        // Normalization removes the oneOf of a discriminator base, leaving a schema without a type. Upstream treats such
        // a request body as any type, which is nullable, even when the body is required.
        val schema = ModelUtils.getReferencedSchema(openAPI, ModelUtils.getSchemaFromRequestBody(body))
        if (schema?.discriminator != null) {
            parameter.isNullable = ModelUtils.isNullable(schema)
        }
        return parameter
    }

    override fun postProcessParameter(parameter: CodegenParameter) {
        super.postProcessParameter(parameter)
        parameter.vendorExtensions[STRING_VALUE_EXTENSION] = parameter.hasStringValue()
    }

    override fun toEnumValue(value: String, datatype: String): String {
        // Upstream recognizes numeric and Boolean enum values by their qualified Kotlin types.
        val enumType = when (datatype) {
            "Int", "Long", "Boolean", "Double", "Float" -> "kotlin.$datatype"
            else -> datatype
        }
        return super.toEnumValue(value, enumType)
    }

    override fun toDefaultValue(property: CodegenProperty, schema: Schema<*>): String? {
        val referencedSchema = ModelUtils.getReferencedSchema(openAPI, schema)
        // Upstream calls create on the URI import mapping, which is String in the multiplatform library.
        if (ModelUtils.isURISchema(referencedSchema) && referencedSchema.default != null) {
            return "\"${escapeText(referencedSchema.default.toString())}\""
        }
        val defaultValue = super.toDefaultValue(property, schema)
        // A Long enum default selects the entry with the same value, but upstream adds an L suffix that entry values lack.
        val isLongEnum = !referencedSchema.enum.isNullOrEmpty() && ModelUtils.isLongSchema(referencedSchema)
        return if (isLongEnum) defaultValue?.removeSuffix("L") else defaultValue
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

    // Upstream derives Pascal case names from escaped names, so a property named `for` produced an enum class named `For`
    // in backticks. Keep the backticks only for names that are not plain identifiers. Reserved words are lowercase, so
    // Pascal case names never match them.
    private fun unescapedTypeName(name: String): String {
        val unescaped = name.removeSurrounding("`")
        return if (IDENTIFIER.matches(unescaped)) unescaped else name
    }

    // Whether request templates can use the value, or every item of a list, as text without conversion. Optional
    // parameters are used only after a null check.
    private fun CodegenParameter.hasStringValue(): Boolean {
        val valueType = if (isContainer) items?.takeUnless { it.isEnum }?.dataType else dataType.takeUnless { isEnum }
        return valueType == "String" && !(required && isNullable)
    }

    private fun widenUnformattedInteger(schema: Schema<*>) {
        // An integer without a format is unbounded; APIs commonly omit int64 for identifiers.
        if (schema.`$ref` == null && ModelUtils.isIntegerSchema(schema) && schema.format == null) {
            schema.format = INT64_FORMAT
        }
    }

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
        private const val INT64_FORMAT = "int64"
        private const val JSON_ELEMENT_TYPE = "JsonElement"
        private const val STRING_VALUE_EXTENSION = "x-string-value"
        private val IDENTIFIER = Regex("[A-Za-z][A-Za-z0-9_]*")
        private val QUALIFIED_TYPE = Regex("(?<![\\w.])(?:\\w+\\.)+\\w+(?![\\w.])")

        // Kotlin's hard keywords, which are never identifiers unless escaped.
        private val HARD_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null",
            "object", "package", "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var",
            "when", "while",
        )
    }
}
