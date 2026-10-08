package io.technoirlab.openapi.kotlin.generator.internal

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Content
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.Parameter
import io.swagger.v3.oas.models.responses.ApiResponse
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Visits each schema reachable from an OpenAPI document once, including references and nested schemas.
 *
 * The visitor receives the schema and the name of its owner: the component that declares it or the operation ID
 * of the operation that uses it. Owners are reported in a stable order: sorted components first, then paths and
 * webhooks in document order.
 */
internal class SchemaTraversal(
    private val visitor: (schema: Schema<*>, owner: String?) -> Unit,
) {
    private val visited: MutableSet<Schema<*>> = Collections.newSetFromMap(IdentityHashMap())

    fun traverse(openAPI: OpenAPI) {
        val components = openAPI.components
        components?.schemas?.toSortedMap()?.forEach { (name, schema) -> traverseSchema(schema, name) }
        components?.parameters?.toSortedMap()?.forEach { (name, parameter) -> traverseParameter(parameter, name) }
        components?.headers?.toSortedMap()?.forEach { (name, header) -> traverseSchema(header.schema, name) }
        components?.requestBodies?.toSortedMap()?.forEach { (name, body) -> traverseContent(body.content, name) }
        components?.responses?.toSortedMap()?.forEach { (name, response) -> traverseResponse(response, name) }
        openAPI.paths?.values?.forEach(::traversePathItem)
        openAPI.webhooks?.values?.forEach(::traversePathItem)
    }

    private fun traversePathItem(pathItem: PathItem) {
        pathItem.parameters?.forEach { traverseParameter(it, owner = null) }
        pathItem.readOperations().forEach { operation ->
            val owner = operation.operationId
            operation.parameters?.forEach { traverseParameter(it, owner) }
            traverseContent(operation.requestBody?.content, owner)
            operation.responses?.values?.forEach { traverseResponse(it, owner) }
            operation.callbacks?.values?.forEach { callback -> callback.values.forEach(::traversePathItem) }
        }
    }

    private fun traverseParameter(parameter: Parameter, owner: String?) {
        traverseSchema(parameter.schema, owner)
        traverseContent(parameter.content, owner)
    }

    private fun traverseResponse(response: ApiResponse, owner: String?) {
        traverseContent(response.content, owner)
        response.headers?.values?.forEach { traverseSchema(it.schema, owner) }
    }

    private fun traverseContent(content: Content?, owner: String?) {
        content?.values?.forEach { traverseSchema(it.schema, owner) }
    }

    private fun traverseSchema(schema: Schema<*>?, owner: String?) {
        if (schema == null || !visited.add(schema)) {
            return
        }
        visitor(schema, owner)
        schema.properties?.values?.forEach { traverseSchema(it, owner) }
        traverseSchema(schema.items, owner)
        traverseSchema(schema.additionalProperties as? Schema<*>, owner)
        schema.allOf?.forEach { traverseSchema(it, owner) }
        schema.oneOf?.forEach { traverseSchema(it, owner) }
        schema.anyOf?.forEach { traverseSchema(it, owner) }
        traverseSchema(schema.not, owner)
    }
}
