// Property-based invariants of the diff engine:
//   1. diff(x, x) is empty                          (identity)
//   2. diff(x, y) reproduces identically            (determinism)
//   3. diff(x.canonical(), y.canonical()) == diff(x, y)   (canonicalization)
//   4. the result is always sorted by changeOrder   (ordering)
//   5. diff(y, x) mirrors diff(x, y): every change has a counterpart with
//      the inverse kind and swapped from/to (direction is explicit, so
//      locations are NOT asserted equal — they can legitimately differ,
//      e.g. the path in an OPERATION_PATH_CHANGED location).

package dev.bloopdex.contractlens.core.diff

import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import dev.bloopdex.contractlens.core.serialization.canonicalJsonBytes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll

class DiffPropertyTest :
    FunSpec({

        fun schemaNodeArb(depth: Int): Arb<SchemaNode> {
            val leaf: Arb<SchemaNode> =
                Arb.choice(
                    Arb.element("string", "integer", "boolean", "number").map {
                        SchemaNode(NodeType.SCALAR, listOf(it), null, emptyMap(), emptyList(), null, emptyList(), false, null, null, false, "leaf")
                    },
                    Arb.int(1, 10).map {
                        SchemaNode(NodeType.ENUM, listOf("string"), null, emptyMap(), emptyList(), null, listOf("v$it"), false, null, null, false, "enum")
                    },
                    Arb.boolean().map {
                        SchemaNode(NodeType.SCALAR, listOf("string"), null, emptyMap(), emptyList(), null, emptyList(), it, null, null, false, "leaf")
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
                Arb.bind(schemaNodeArb(1), Arb.boolean()) { schema: SchemaNode, required: Boolean ->
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

        /**
         * Direction-invariant identity of a change: the canonical kind of
         * its inverse pair (the alphabetically first name) plus the
         * sorted from/to summaries. A change and its mirror in the
         * reversed diff must share this key.
         */
        fun mirrorKey(change: ContractChange): String {
            val canonicalKind =
                inverseKinds[change.kind]
                    ?.let { listOf(it, change.kind).minBy { kind -> kind.name } }
                    ?: change.kind
            val values = listOf(change.from?.summary ?: "", change.to?.summary ?: "").sorted()
            return "$canonicalKind|${change.target}|${values.joinToString("|")}"
        }

        test("diff of a contract with itself is empty") {
            checkAll(surfaceArb()) { surface: ContractSurface ->
                DiffEngine.diff(surface, surface) shouldBe emptyList()
            }
        }

        test("diff reproduces identically (byte-identical serialization)") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val first = canonicalJsonBytes(DiffEngine.diff(old, new))
                val second = canonicalJsonBytes(DiffEngine.diff(old, new))
                first shouldBe second
            }
        }

        test("diffing canonicalized inputs equals diffing the originals") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                DiffEngine.diff(old.canonical(), new.canonical()) shouldBe DiffEngine.diff(old, new)
            }
        }

        test("the result is always sorted by changeOrder") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val changes = DiffEngine.diff(old, new)
                changes shouldBe changes.sortedWith(changeOrder)
            }
        }

        test("reversing the inputs mirrors every change (inverse kind, swapped from/to)") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val forward = DiffEngine.diff(old, new)
                val reverse = DiffEngine.diff(new, old)
                reverse.map(::mirrorKey).sorted() shouldBe forward.map(::mirrorKey).sorted()
            }
        }

        test("serialized changes are stable across runs for the same pair") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val changes = DiffEngine.diff(old, new)
                canonicalJsonBytes(changes) shouldBe canonicalJsonBytes(changes.map { it.copy() })
            }
        }
    })
