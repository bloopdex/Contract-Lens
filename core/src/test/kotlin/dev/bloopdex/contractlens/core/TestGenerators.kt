// Shared property-test generators for the canonical model (Phase 4).
// Used by the classifier property tests; the Phase 2/3 property suites
// keep their own local copies so their files remain reviewable in
// isolation.

package dev.bloopdex.contractlens.core

import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull

fun schemaNodeArb(depth: Int): Arb<SchemaNode> {
    val leaf: Arb<SchemaNode> =
        Arb.choice(
            Arb.element("string", "integer", "boolean", "number").map {
                SchemaNode(NodeType.SCALAR, listOf(it), null, emptyMap(), emptyList(), null, emptyList(), false, null, null, false, "leaf")
            },
            Arb.element("a", "b").map {
                SchemaNode(
                    NodeType.ENUM,
                    listOf("string"),
                    null,
                    emptyMap(),
                    emptyList(),
                    null,
                    listOf("v-$it"),
                    false,
                    null,
                    null,
                    false,
                    "enum",
                )
            },
            Arb.boolean().map {
                SchemaNode(
                    NodeType.SCALAR,
                    listOf("string"),
                    null,
                    emptyMap(),
                    emptyList(),
                    null,
                    emptyList(),
                    false,
                    null,
                    null,
                    it,
                    "leaf",
                )
            },
        )
    if (depth <= 0) return leaf
    val propertyArb: Arb<Pair<String, SchemaNode>> =
        Arb.bind(Arb.element("a", "b", "c"), leaf) { key: String, value: SchemaNode -> key to value }
    val objectNode: Arb<SchemaNode> =
        Arb.bind(
            Arb.map<String, SchemaNode>(propertyArb, 0, 3),
            Arb.list(Arb.element("a", "b", "c"), 0..3),
        ) { properties: Map<String, SchemaNode>, required: List<String> ->
            SchemaNode(
                NodeType.OBJECT,
                listOf("object"),
                null,
                properties,
                required.distinct().filter { it in properties.keys },
                null,
                emptyList(),
                false,
                null,
                null,
                false,
                "obj",
            )
        }
    val arrayNode: Arb<SchemaNode> =
        leaf.map {
            SchemaNode(NodeType.ARRAY, listOf("array"), null, emptyMap(), emptyList(), it, emptyList(), false, null, null, false, "arr")
        }
    return Arb.choice(leaf, objectNode, arrayNode).map {
        it.copy(
            types = it.types.sorted(),
            required = it.required.sorted().distinct(),
            properties = it.properties.toSortedMap(),
        )
    }
}

fun operationArb(): Arb<Operation> {
    val paths = listOf("/a", "/b/{id}", "/c/{name}")
    val methods = listOf("get", "post")
    val parameterArb: Arb<Parameter> =
        Arb.bind(
            Arb.element("p", "q", "r"),
            Arb.element("query", "header"),
            Arb.boolean(),
            schemaNodeArb(1).orNull(),
        ) { name: String, locationIn: String, required: Boolean, schema: SchemaNode? ->
            Parameter(name, locationIn, required, schema, "p.$name")
        }
    val bodyArb: Arb<RequestBody?> =
        Arb
            .bind(schemaNodeArb(1), Arb.boolean()) { schema: SchemaNode, required: Boolean ->
                RequestBody(required, mapOf("application/json" to schema))
            }.orNull()
    val responseArb: Arb<Pair<String, Response>> =
        Arb.bind(Arb.element("200", "201", "404"), schemaNodeArb(1)) { status: String, schema: SchemaNode ->
            status to Response(mapOf("application/json" to schema), "r.$status")
        }
    return Arb.bind(
        Arb.element(methods),
        Arb.element(paths),
        Arb.list(parameterArb, 0..3),
        bodyArb,
        Arb.map<String, Response>(responseArb, 0, 2),
    ) { method: String, path: String, parameters: List<Parameter>, body: RequestBody?, responses: Map<String, Response> ->
        Operation(
            method = method,
            path = path,
            pathIdentity = path.replace(Regex("\\{[^}]*\\}"), "{}"),
            parameters = parameters.distinctBy { it.`in` to it.name },
            requestBody = body,
            responses = responses.toSortedMap(),
            location = "paths.$path.$method",
        )
    }
}

fun surfaceArb(): Arb<ContractSurface> =
    Arb.list<Operation>(operationArb(), 1..3).map { operations ->
        ContractSurface("test", "openapi", "3.0.3", operations)
    }
