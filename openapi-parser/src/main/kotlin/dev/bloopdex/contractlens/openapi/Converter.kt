// Conversion from the swagger-core object model into the canonical
// ContractLens model. All normalization (sorting, dedup, lowercase
// status keys, path identity) happens here so the domain model only
// ever holds canonical state.

package dev.bloopdex.contractlens.openapi

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.Constraints
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import dev.bloopdex.contractlens.core.model.normalizeStatusKey
import dev.bloopdex.contractlens.core.model.pathIdentity
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.media.Schema

internal class Converter(
    private val contractName: String,
    private val formatVersion: String,
) {
    private val maxRefDepth = 64

    fun convert(openApi: OpenAPI): ContractSurface {
        val components = openApi.components
        val operations =
            openApi.paths
                .orEmpty()
                .toSortedMap()
                .flatMap { (path, pathItem) -> convertPathItem(path, pathItem, components) }
                .sortedWith(compareBy({ it.pathIdentity }, { it.method }))
        return ContractSurface(
            name = contractName,
            kind = "openapi",
            formatVersion = formatVersion,
            operations = operations,
        )
    }

    private fun convertPathItem(
        path: String,
        item: PathItem,
        components: Components?,
    ): List<Operation> =
        item
            .readOperationsMap()
            .entries
            .sortedBy { it.key.toString() }
            .map { (method, operation) ->
                val baseLocation = "paths.$path.${method.toString().lowercase()}"
                convertOperation(path, method.toString().lowercase(), item, operation, components, baseLocation)
            }

    private fun convertOperation(
        path: String,
        method: String,
        pathItem: PathItem,
        operation: io.swagger.v3.oas.models.Operation,
        components: Components?,
        baseLocation: String,
    ): Operation {
        val parameters = convertParameters(pathItem.parameters, operation.parameters, components, baseLocation)
        val requestBody = operation.requestBody?.let { convertRequestBody(it, components, "$baseLocation.requestBody") }
        val rawResponses = operation.responses.orEmpty()
        // Two keys that normalize to the same status (e.g. "2XX" and
        // "2xx") would silently collide in a sorted map keyed by the
        // normalized form — fail loudly instead.
        if (rawResponses.keys
                .map(::normalizeStatusKey)
                .toSet()
                .size != rawResponses.size
        ) {
            throw ContractError.InvalidStructure("duplicate status keys in $baseLocation.responses")
        }
        val responses =
            rawResponses
                .toSortedMap(compareBy { normalizeStatusKey(it) })
                .mapValues { (status, response) ->
                    val resolved = resolveComponentResponse(response, components, "$baseLocation.responses.$status")
                    convertResponse(resolved, components, "$baseLocation.responses.$status")
                }
        return Operation(
            method = method,
            path = path,
            pathIdentity = pathIdentity(path),
            parameters = parameters,
            requestBody = requestBody,
            responses = responses,
            location = baseLocation,
        )
    }

    // --- parameters -------------------------------------------------

    private fun convertParameters(
        pathLevel: List<io.swagger.v3.oas.models.parameters.Parameter>?,
        operationLevel: List<io.swagger.v3.oas.models.parameters.Parameter>?,
        components: Components?,
        baseLocation: String,
    ): List<Parameter> {
        // Operation-level parameters override path-level ones with the
        // same (name, in) identity — the OpenAPI inheritance rule.
        val byIdentity = linkedMapOf<String, io.swagger.v3.oas.models.parameters.Parameter>()
        for (parameter in pathLevel.orEmpty()) {
            byIdentity[parameterIdentity(parameter)] = parameter
        }
        for (parameter in operationLevel.orEmpty()) {
            byIdentity[parameterIdentity(parameter)] = parameter
        }
        return byIdentity.values
            .map { parameter ->
                val identity = parameterIdentity(parameter)
                val resolved = resolveComponentParameter(parameter, components, "$baseLocation.parameters.$identity")
                val schema =
                    resolved.schema?.let {
                        convertSchema(it, components, mutableSetOf(), 0, "$baseLocation.parameters.$identity.schema")
                    }
                Parameter(
                    name = resolved.name ?: identity.substringAfter(":"),
                    `in` = resolved.`in` ?: identity.substringBefore(":"),
                    required = resolved.required == true,
                    schema = schema,
                    location = "$baseLocation.parameters.$identity",
                )
            }.sortedWith(compareBy(Parameter::`in`).thenBy(Parameter::name))
    }

    private fun parameterIdentity(p: io.swagger.v3.oas.models.parameters.Parameter): String {
        val name = p.name ?: refName(p.`$ref`)
        val where = p.`in` ?: "query"
        return "$where:$name"
    }

    private fun resolveComponentParameter(
        parameter: io.swagger.v3.oas.models.parameters.Parameter,
        components: Components?,
        location: String,
    ): io.swagger.v3.oas.models.parameters.Parameter =
        if (parameter.`$ref` != null) {
            resolveComponent(parameter.`$ref`, components?.parameters, "parameter", location) {
                it as io.swagger.v3.oas.models.parameters.Parameter
            }
        } else {
            parameter
        }

    // --- request/response bodies -------------------------------------

    private fun convertRequestBody(
        body: io.swagger.v3.oas.models.parameters.RequestBody,
        components: Components?,
        location: String,
    ): RequestBody {
        val resolved =
            if (body.`$ref` != null) {
                resolveComponent(
                    body.`$ref`,
                    components?.requestBodies,
                    "requestBody",
                    location,
                ) { it as io.swagger.v3.oas.models.parameters.RequestBody }
            } else {
                body
            }
        val content =
            resolved.content
                .orEmpty()
                .entries
                .associate { (contentType, media) ->
                    val schema =
                        media.schema
                            ?: throw ContractError.InvalidStructure("request body content '$contentType' has no schema at $location")
                    contentType to convertSchema(schema, components, mutableSetOf(), 0, "$location.content.$contentType.schema")
                }.toSortedMap()
        return RequestBody(required = resolved.required == true, content = content)
    }

    private fun resolveComponentResponse(
        response: io.swagger.v3.oas.models.responses.ApiResponse,
        components: Components?,
        location: String,
    ): io.swagger.v3.oas.models.responses.ApiResponse =
        if (response.`$ref` != null) {
            resolveComponent(
                response.`$ref`,
                components?.responses,
                "response",
                location,
            ) { it as io.swagger.v3.oas.models.responses.ApiResponse }
        } else {
            response
        }

    private fun convertResponse(
        response: io.swagger.v3.oas.models.responses.ApiResponse,
        components: Components?,
        location: String,
    ): Response {
        val content =
            response.content
                .orEmpty()
                .entries
                .associate { (contentType, media) ->
                    val schema =
                        media.schema
                            ?: throw ContractError.InvalidStructure("response content '$contentType' has no schema at $location")
                    contentType to convertSchema(schema, components, mutableSetOf(), 0, "$location.content.$contentType.schema")
                }.toSortedMap()
        return Response(content = content, location = location)
    }

    // --- schemas -----------------------------------------------------

    private fun convertSchema(
        schema: Schema<*>,
        components: Components?,
        resolving: MutableSet<String>,
        depth: Int,
        location: String,
    ): SchemaNode {
        if (depth > maxRefDepth) {
            throw ContractError.DepthExceeded(location, maxRefDepth)
        }

        // $ref: resolve against the target (cycle cut -> REF node whose
        // refTarget preserves the name for explanation, ADR-001). On a
        // successful resolution the ref name is still stamped as
        // explanatory metadata — it is NOT part of structural identity.
        val ref = schema.`$ref`
        if (ref != null) {
            if (isLocalRef(ref)) {
                val target = resolveComponent(ref, components?.schemas, "schema", location) { it as Schema<*> }
                if (!resolving.add(ref)) {
                    // Cycle: the target is already being resolved above us.
                    return refNode(ref, location)
                }
                try {
                    return convertSchema(target, components, resolving, depth + 1, location).copy(refTarget = ref)
                } finally {
                    resolving.remove(ref)
                }
            }
            throw ContractError.UnsupportedReference(ref, location)
        }

        val properties =
            schema.properties
                .orEmpty()
                .entries
                .associate { (name, child) ->
                    name to convertSchema(child, components, resolving, depth + 1, "$location.properties.$name")
                }.toSortedMap()
        val required =
            schema.required
                .orEmpty()
                .sorted()
                .distinct()
        val items =
            schema.items?.let {
                convertSchema(it, components, resolving, depth + 1, "$location.items")
            }
        val enumValues = schema.enum?.map(::stringifyEnumValue)

        val types =
            schema.types
                .orEmpty()
                .filterNot { it == "null" }
                .sorted()
        val legacyType = schema.type
        val allTypes = if (types.isNotEmpty()) types else listOfNotNull(legacyType).sorted()
        val nullable = schema.nullable == true || "null" in schema.types.orEmpty()

        val nodeType =
            when {
                enumValues != null -> NodeType.ENUM
                properties.isNotEmpty() || schema.properties != null -> NodeType.OBJECT
                items != null -> NodeType.ARRAY
                allTypes.isNotEmpty() -> NodeType.SCALAR
                else -> NodeType.ANY
            }

        return SchemaNode(
            nodeType = nodeType,
            types = allTypes,
            format = schema.format,
            properties = properties,
            required = required,
            items = items,
            enumValues = enumValues.orEmpty(),
            nullable = nullable,
            refTarget = null,
            constraints =
                Constraints(
                    // 3.1 numeric exclusiveMinimum/exclusiveMaximum live in
                    // dedicated swagger-core fields, not in minimum/maximum.
                    minimum = schema.minimum?.toDouble() ?: schema.exclusiveMinimumValue?.toDouble(),
                    maximum = schema.maximum?.toDouble() ?: schema.exclusiveMaximumValue?.toDouble(),
                    minLength = schema.minLength,
                    maxLength = schema.maxLength,
                    pattern = schema.pattern,
                    minItems = schema.minItems,
                    maxItems = schema.maxItems,
                ).takeUnless {
                    it.minimum == null &&
                        it.maximum == null &&
                        it.minLength == null &&
                        it.maxLength == null &&
                        it.pattern == null &&
                        it.minItems == null &&
                        it.maxItems == null
                },
            defaultPresent = schema.default != null,
            location = location,
        )
    }

    private fun refNode(
        ref: String,
        location: String,
    ): SchemaNode =
        SchemaNode(
            nodeType = NodeType.REF,
            types = emptyList(),
            format = null,
            properties = emptyMap(),
            required = emptyList(),
            items = null,
            enumValues = emptyList(),
            nullable = false,
            refTarget = ref,
            constraints = null,
            defaultPresent = false,
            location = location,
        )

    // --- component lookup --------------------------------------------

    private fun isLocalRef(ref: String): Boolean = ref.startsWith("#/") || ref.startsWith("#")

    private fun <T> resolveComponent(
        ref: String,
        registry: Map<String, T>?,
        kind: String,
        location: String,
        cast: (T) -> Any,
    ): T {
        if (registry == null || registry.isEmpty()) {
            throw ContractError.UnresolvedReference(ref, location)
        }
        val pointer = parsePointer(ref)
        val name = pointer.last()
        val target = registry[name] ?: throw ContractError.UnresolvedReference(ref, location)
        @Suppress("UNCHECKED_CAST")
        return cast(target) as T
    }

    /** "#/components/schemas/User" -> ["components", "schemas", "User"]. */
    private fun parsePointer(ref: String): List<String> =
        ref
            .removePrefix("#")
            .split('/')
            .filter { it.isNotEmpty() }
            .map { it.replace("~1", "/").replace("~0", "~") }

    private fun refName(ref: String?): String = ref?.substringAfterLast('/') ?: "unknown"

    private fun stringifyEnumValue(value: Any?): String =
        when (value) {
            null -> "null"
            is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
            else -> value.toString()
        }
}
