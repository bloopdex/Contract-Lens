// GraphQL SDL -> canonical model adapter (Phase 4 groundwork).
//
// Parses a GraphQL SDL document (graphql-java's SchemaParser, ADR-005
// philosophy: the mature library, never a hand-rolled parser) and maps
// it into the canonical ContractSurface so the SHARED diff engine and
// classifier run unchanged (Phase 0: one canonical model, per-format
// adapters; ADR-004 deferred GraphQL to its own parser work).
//
// Mapping rules (documented groundwork contract):
//   - query fields become operations {method: "query", path:
//     "query.<field>"}; mutation fields become {method: "mutation",
//     path: "mutation.<field>"}
//   - field arguments become parameters (in: "argument")
//   - each root field's return type becomes its "data" response schema
//   - built-in scalars map to the canonical types (String/ID -> string,
//     Int -> integer, Float -> number, Boolean -> boolean); custom
//     scalars become SCALAR nodes typed with their scalar name
//   - enums map to ENUM nodes; input/object types resolve inline;
//     recursive references become REF nodes (refTarget: "graphql:<name>")
//     with a cycle guard
//   - NonNull controls `required`/`nullable` exactly as in the schema
//
// Depth is bounded (DEPTH_EXCEEDED, the existing typed error); malformed
// SDL fails with MALFORMED_DOCUMENT or INVALID_STRUCTURE.

package dev.bloopdex.contractlens.graphql

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.error.MAX_INPUT_BYTES
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import graphql.language.EnumTypeDefinition
import graphql.language.EnumValueDefinition
import graphql.language.FieldDefinition
import graphql.language.InputObjectTypeDefinition
import graphql.language.ListType
import graphql.language.NonNullType
import graphql.language.ObjectTypeDefinition
import graphql.language.ScalarTypeDefinition
import graphql.language.Type
import graphql.language.TypeName
import graphql.schema.idl.SchemaParser

const val MAX_SCHEMA_DEPTH = 64

class GraphQlParser {
    fun parse(
        text: String,
        contractName: String,
    ): ContractSurface {
        if (text.length > MAX_INPUT_BYTES) {
            throw ContractError.InputTooLarge(contractName, text.length.toLong())
        }
        val registry =
            try {
                SchemaParser().parse(text)
            } catch (e: Exception) {
                throw ContractError.MalformedDocument("GraphQL SDL: ${e.message}", e)
            }
        val operations = mutableListOf<Operation>()
        registry.getTypeOrNull("Query")?.let { queryType ->
            if (queryType !is ObjectTypeDefinition) {
                throw ContractError.InvalidStructure("type 'Query' is not an object type")
            }
            operations += rootOperations("query", queryType, registry)
        }
        registry.getTypeOrNull("Mutation")?.let { mutationType ->
            if (mutationType !is ObjectTypeDefinition) {
                throw ContractError.InvalidStructure("type 'Mutation' is not an object type")
            }
            operations += rootOperations("mutation", mutationType, registry)
        }
        if (operations.isEmpty()) {
            throw ContractError.InvalidStructure("no 'Query' or 'Mutation' type defined in the SDL document")
        }
        return ContractSurface(
            name = contractName,
            kind = "graphql",
            formatVersion = "sdl",
            operations = operations.sortedWith(compareBy({ it.pathIdentity }, { it.method })),
        )
    }

    private fun rootOperations(
        kind: String,
        type: ObjectTypeDefinition,
        registry: graphql.schema.idl.TypeDefinitionRegistry,
    ): List<Operation> =
        type.fieldDefinitions.map { field ->
            val parameters =
                field.inputValueDefinitions.map { argument ->
                    Parameter(
                        name = argument.name,
                        `in` = "argument",
                        required = argument.type is NonNullType,
                        schema = resolveType(argument.type, registry, mutableSetOf(), 0),
                        location = "graphql.$kind.${field.name}.arguments.${argument.name}",
                    )
                }
            Operation(
                method = kind,
                path = "$kind.${field.name}",
                pathIdentity = "$kind.${field.name}",
                parameters = parameters,
                requestBody = null,
                responses =
                    mapOf(
                        "data" to
                            Response(
                                content =
                                    mapOf(
                                        "application/json" to
                                            resolveType(field.type, registry, mutableSetOf(), 0),
                                    ),
                                location = "graphql.$kind.${field.name}.data",
                            ),
                    ),
                location = "graphql.$kind.${field.name}",
            )
        }

    private fun resolveType(
        type: Type<*>,
        registry: graphql.schema.idl.TypeDefinitionRegistry,
        stack: MutableSet<String>,
        depth: Int,
    ): SchemaNode {
        if (depth > MAX_SCHEMA_DEPTH) {
            throw ContractError.DepthExceeded("graphql:${typeName(type)}", MAX_SCHEMA_DEPTH)
        }
        return when (type) {
            is NonNullType -> resolveType(type.type, registry, stack, depth).copy(nullable = false)
            is ListType -> arrayNode(resolveType(type.type, registry, stack, depth + 1), "list")
            is TypeName ->
                resolveTypeName(
                    type.name ?: throw ContractError.InvalidStructure("unnamed GraphQL type reference"),
                    registry,
                    stack,
                    depth,
                )
            else -> throw ContractError.InvalidStructure("unsupported GraphQL type reference: ${type.javaClass.simpleName}")
        }
    }

    private fun resolveTypeName(
        name: String,
        registry: graphql.schema.idl.TypeDefinitionRegistry,
        stack: MutableSet<String>,
        depth: Int,
    ): SchemaNode {
        when (name) {
            "String", "ID" -> return scalarNode(listOf("string"), "scalar.$name")
            "Int" -> return scalarNode(listOf("integer"), "scalar.$name")
            "Float" -> return scalarNode(listOf("number"), "scalar.$name")
            "Boolean" -> return scalarNode(listOf("boolean"), "scalar.$name")
        }
        val definition =
            registry.getTypeOrNull(name)
                ?: throw ContractError.InvalidStructure("unknown GraphQL type '$name'")
        return when (definition) {
            is EnumTypeDefinition ->
                SchemaNode(
                    nodeType = NodeType.ENUM,
                    types = listOf("string"),
                    format = null,
                    properties = emptyMap(),
                    required = emptyList(),
                    items = null,
                    enumValues = definition.enumValueDefinitions.map(EnumValueDefinition::getName).toList(),
                    nullable = true,
                    refTarget = null,
                    constraints = null,
                    defaultPresent = false,
                    location = "graphql.enum.$name",
                )
            is ScalarTypeDefinition -> scalarNode(listOf(name), "graphql.scalar.$name")
            is ObjectTypeDefinition, is InputObjectTypeDefinition -> {
                if (!stack.add(name)) {
                    return SchemaNode(
                        nodeType = NodeType.REF,
                        types = emptyList(),
                        format = null,
                        properties = emptyMap(),
                        required = emptyList(),
                        items = null,
                        enumValues = emptyList(),
                        nullable = true,
                        refTarget = "graphql:$name",
                        constraints = null,
                        defaultPresent = false,
                        location = "graphql.ref.$name",
                    )
                }
                val node = resolveObjectLike(name, definition, registry, stack, depth)
                stack.remove(name)
                node
            }
            else -> throw ContractError.InvalidStructure(
                "unsupported GraphQL type definition '${definition.javaClass.simpleName}' for '$name'",
            )
        }
    }

    private fun resolveObjectLike(
        name: String,
        definition: Any,
        registry: graphql.schema.idl.TypeDefinitionRegistry,
        stack: MutableSet<String>,
        depth: Int,
    ): SchemaNode {
        val fields: List<FieldDefinition>
        val properties = linkedMapOf<String, SchemaNode>()
        val required = mutableListOf<String>()
        if (definition is ObjectTypeDefinition) {
            fields = definition.fieldDefinitions
        } else if (definition is InputObjectTypeDefinition) {
            for (inputField in definition.inputValueDefinitions) {
                properties[inputField.name] = resolveType(inputField.type, registry, stack, depth + 1)
                if (inputField.type is NonNullType) required += inputField.name
            }
            fields = emptyList()
        } else {
            throw ContractError.InvalidStructure("unsupported object-like definition for '$name'")
        }
        for (field in fields) {
            properties[field.name] = resolveType(field.type, registry, stack, depth + 1)
            if (field.type is NonNullType) required += field.name
        }
        return SchemaNode(
            nodeType = NodeType.OBJECT,
            types = listOf("object"),
            format = null,
            properties = properties,
            required = required,
            items = null,
            enumValues = emptyList(),
            nullable = true,
            refTarget = null,
            constraints = null,
            defaultPresent = false,
            location = "graphql.type.$name",
        )
    }

    private fun scalarNode(
        types: List<String>,
        location: String,
    ): SchemaNode =
        SchemaNode(
            nodeType = NodeType.SCALAR,
            types = types,
            format = null,
            properties = emptyMap(),
            required = emptyList(),
            items = null,
            enumValues = emptyList(),
            nullable = true,
            refTarget = null,
            constraints = null,
            defaultPresent = false,
            location = location,
        )

    private fun arrayNode(
        items: SchemaNode,
        location: String,
    ): SchemaNode =
        SchemaNode(
            nodeType = NodeType.ARRAY,
            types = listOf("array"),
            format = null,
            properties = emptyMap(),
            required = emptyList(),
            items = items,
            enumValues = emptyList(),
            nullable = true,
            refTarget = null,
            constraints = null,
            defaultPresent = false,
            location = location,
        )

    private fun typeName(type: Type<*>): String =
        when (type) {
            is NonNullType -> typeName(type.type)
            is ListType -> "[${typeName(type.type)}]"
            is TypeName -> type.name ?: throw ContractError.InvalidStructure("unnamed GraphQL type reference")
            else -> type.javaClass.simpleName
        }
}
