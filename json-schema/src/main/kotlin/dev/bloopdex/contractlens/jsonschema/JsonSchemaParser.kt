// JSON Schema event contract -> canonical model adapter. ADR-004 deferred JSON Schema event contracts to their own
// parser work; the canonical SchemaNode already covers the
// JSON Schema concepts (type, required, properties, enum, constraints,
// items, nullability).
//
// Mapping rules (documented groundwork contract):
//   - the whole document is ONE event contract: a single synthetic
//     operation {method: "event", path: "/<title or name>"} whose
//     "schema" response carries the mapped root schema — the shared
//     diff engine and classifier then run unchanged
//   - type -> canonical types (scalar / array / object); "null" in the
//     type union sets nullable
//   - properties/required/items/enum/constraints/default map directly
//   - local $refs are NOT resolved (REF nodes keep the pointer as
//     refTarget); cross-document refs are rejected
//   - depth is bounded (DEPTH_EXCEEDED, the existing typed error)
//   - malformed JSON fails with MALFORMED_DOCUMENT; wrong shapes fail
//     with INVALID_STRUCTURE
//
// Known limitation (documented): the draft is not sniffed — the adapter
// maps the stable JSON Schema core vocabulary (2020-12 shape) and treats
// unrecognized keywords as absent, never as errors.

package dev.bloopdex.contractlens.jsonschema

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.error.MAX_INPUT_BYTES
import dev.bloopdex.contractlens.core.model.Constraints
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

const val MAX_SCHEMA_DEPTH = 64

class JsonSchemaParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        text: String,
        contractName: String,
    ): ContractSurface {
        if (text.length > MAX_INPUT_BYTES) {
            throw ContractError.InputTooLarge(contractName, text.length.toLong())
        }
        val root =
            try {
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                throw ContractError.MalformedDocument("JSON Schema: ${e.message}", e)
            }
        if (root !is JsonObject) {
            throw ContractError.InvalidStructure("the JSON Schema document must be an object")
        }
        val title = (root["title"] as? JsonPrimitive)?.contentOrNull
        val eventPath = "/" + (title?.takeIf { it.isNotBlank() } ?: contractName)
        return ContractSurface(
            name = contractName,
            kind = "json-schema",
            formatVersion = "2020-12",
            operations =
                listOf(
                    Operation(
                        method = "event",
                        path = eventPath,
                        pathIdentity = eventPath,
                        parameters = emptyList(),
                        requestBody = null,
                        responses =
                            mapOf(
                                "schema" to
                                    Response(
                                        content = mapOf("application/json" to mapSchema(root, "event.$contractName", mutableSetOf(), 0)),
                                        location = "event.$contractName.schema",
                                    ),
                            ),
                        location = "event.$contractName",
                    ),
                ),
        )
    }

    private fun mapSchema(
        schema: JsonObject,
        location: String,
        stack: MutableSet<String>,
        depth: Int,
    ): SchemaNode {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw ContractError.DepthExceeded(location, MAX_SCHEMA_DEPTH)
        }
        val ref = (schema["\$ref"] as? JsonPrimitive)?.contentOrNull
        if (ref != null) {
            if (ref.startsWith("#/")) {
                return SchemaNode(
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
            }
            throw ContractError.InvalidStructure("cross-document \$ref is not supported: '$ref'")
        }

        val typeElement = schema["type"]
        val rawTypes =
            when (typeElement) {
                is JsonPrimitive -> listOf(typeElement.contentOrNull ?: "")
                is JsonArray -> typeElement.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                null -> emptyList()
                else -> throw ContractError.InvalidStructure("invalid 'type' at $location")
            }
        val nullable = "null" in rawTypes
        val types = rawTypes.filter { it != "null" }
        val enumValues =
            (schema["enum"] as? JsonArray).orEmpty().mapNotNull { element ->
                when (element) {
                    is JsonPrimitive -> element.contentOrNull
                    else -> element.toString()
                }
            }
        val nodeType =
            when {
                enumValues.isNotEmpty() -> NodeType.ENUM
                types.firstOrNull() == "object" -> NodeType.OBJECT
                types.firstOrNull() == "array" -> NodeType.ARRAY
                types.isEmpty() -> NodeType.ANY
                else -> NodeType.SCALAR
            }

        val properties = linkedMapOf<String, SchemaNode>()
        val required = mutableListOf<String>()
        (schema["properties"] as? JsonObject)?.forEach { (name, value) ->
            if (value is JsonObject) {
                properties[name] = mapSchema(value, "$location.properties.$name", stack, depth + 1)
            }
        }
        (schema["required"] as? JsonArray)?.forEach { element ->
            (element as? JsonPrimitive)?.contentOrNull?.let { required += it }
        }

        val items = (schema["items"] as? JsonObject)?.let { mapSchema(it, "$location.items", stack, depth + 1) }

        val constraints =
            Constraints(
                minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull,
                maximum = (schema["maximum"] as? JsonPrimitive)?.doubleOrNull,
                minLength = (schema["minLength"] as? JsonPrimitive)?.intOrNull,
                maxLength = (schema["maxLength"] as? JsonPrimitive)?.intOrNull,
                pattern = (schema["pattern"] as? JsonPrimitive)?.contentOrNull,
                minItems = (schema["minItems"] as? JsonPrimitive)?.intOrNull,
                maxItems = (schema["maxItems"] as? JsonPrimitive)?.intOrNull,
            ).takeIf {
                it.minimum != null ||
                    it.maximum != null ||
                    it.minLength != null ||
                    it.maxLength != null ||
                    it.pattern != null ||
                    it.minItems != null ||
                    it.maxItems != null
            }

        return SchemaNode(
            nodeType = nodeType,
            types = types,
            format = (schema["format"] as? JsonPrimitive)?.contentOrNull,
            properties = properties,
            required = required.distinct().filter { it in properties.keys },
            items = items,
            enumValues = enumValues,
            nullable = nullable,
            refTarget = null,
            constraints = constraints,
            defaultPresent = schema.containsKey("default"),
            location = location,
        )
    }
}
