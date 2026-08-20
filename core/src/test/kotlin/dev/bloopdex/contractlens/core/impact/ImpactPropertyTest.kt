// Property-based invariants of the consumer mapper:
//   1. determinism — identical inputs, identical reports
//   2. registry ordering invariance — consumer order is semantically irrelevant
//   3. no phantom consumers — every reported consumer exists in the registry
//   4. no phantom changes — every reported change comes from the input set
//   5. deduplication — duplicated inputs never duplicate impacts
//   6. engine integration — a wildcard consumer receives every change the
//      real engine emits (nothing falls through the location grammar)

package dev.bloopdex.contractlens.core.impact

import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ChangeTarget
import dev.bloopdex.contractlens.core.diff.ChangeValue
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.DiffEngine
import dev.bloopdex.contractlens.core.diff.changeOrder
import dev.bloopdex.contractlens.core.diff.explainChange
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import dev.bloopdex.contractlens.core.registry.Consumer
import dev.bloopdex.contractlens.core.registry.ConsumerKind
import dev.bloopdex.contractlens.core.registry.ConsumerRegistry
import dev.bloopdex.contractlens.core.registry.parseSelector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll

class ImpactPropertyTest :
    FunSpec({

        // --- synthetic mechanics generators (no engine involved) -----

        val syntheticLocations =
            listOf(
                "GET /a → response 200 → schema → properties.x",
                "POST /b/{p} → request body → schema",
                "GET /a → parameter \"limit\" (query)",
                "POST /b/{p}",
                "GET /a",
                "nonsense",
            )

        fun changeArb(): Arb<ContractChange> =
            Arb.bind(
                Arb.element(ChangeKind.entries),
                Arb.element(ChangeTarget.entries),
                Arb.element(syntheticLocations),
                Arb.string(0..8).orNull(),
                Arb.string(0..8).orNull(),
            ) { kind: ChangeKind, target: ChangeTarget, location: String, from: String?, to: String? ->
                val fromValue = from?.let { ChangeValue(it) }
                val toValue = to?.let { ChangeValue(it) }
                ContractChange(
                    kind = kind,
                    target = target,
                    location = location,
                    sourceLocation = null,
                    from = fromValue,
                    to = toValue,
                    explanation = explainChange(kind, location, fromValue, toValue),
                )
            }

        val selectorStrings = listOf("*", "GET /a", "POST /b/{p}", "GET /other")

        fun consumerArb(): Arb<Consumer> =
            Arb.bind(
                Arb.element("consumer-1", "consumer-2"),
                Arb.list(Arb.element(selectorStrings), 1..2),
            ) { id: String, selectors: List<String> ->
                Consumer(
                    id = id,
                    kind = ConsumerKind.FRONTEND,
                    contract = "example-api",
                    selectors = selectors.map { parseSelector(id, it) },
                )
            }

        fun registryArb(): Arb<ConsumerRegistry> =
            Arb.list(consumerArb(), 0..4).map { consumers ->
                ConsumerRegistry(version = 1, consumers = consumers.distinctBy { it.id })
            }

        test("determinism: mapping the same inputs twice yields identical reports") {
            checkAll(Arb.list(changeArb(), 0..6), registryArb()) { changes: List<ContractChange>, registry: ConsumerRegistry ->
                ConsumerMapper.map(changes, registry, "example-api") shouldBe ConsumerMapper.map(changes, registry, "example-api")
            }
        }

        test("registry ordering invariance: consumer order does not change the report") {
            checkAll(Arb.list(changeArb(), 0..6), registryArb()) { changes: List<ContractChange>, registry: ConsumerRegistry ->
                val reversed = ConsumerRegistry(registry.version, registry.consumers.reversed())
                ConsumerMapper.map(changes, reversed, "example-api") shouldBe ConsumerMapper.map(changes, registry, "example-api")
            }
        }

        test("no phantom consumers: every reported consumer exists in the registry") {
            checkAll(Arb.list(changeArb(), 0..6), registryArb()) { changes: List<ContractChange>, registry: ConsumerRegistry ->
                val report = ConsumerMapper.map(changes, registry, "example-api")
                report.impacts.forEach { impact ->
                    (impact.consumer in registry.consumers) shouldBe true
                }
            }
        }

        test("no phantom changes: every reported change comes from the input set") {
            checkAll(Arb.list(changeArb(), 0..6), registryArb()) { changes: List<ContractChange>, registry: ConsumerRegistry ->
                val report = ConsumerMapper.map(changes, registry, "example-api")
                report.changes shouldBe changes.sortedWith(changeOrder)
                report.impacts
                    .flatMap { it.changes.map { entry -> entry.change } }
                    .forEach { change -> (change in changes) shouldBe true }
            }
        }

        test("deduplication: duplicating a change in the input does not duplicate impacts") {
            checkAll(changeArb(), registryArb()) { change: ContractChange, registry: ConsumerRegistry ->
                val doubled = listOf(change, change)
                ConsumerMapper.map(doubled, registry, "example-api").impacts shouldBe
                    ConsumerMapper.map(listOf(change), registry, "example-api").impacts
            }
        }

        test("idempotence: mapping a report's own changes again yields the same impacts") {
            checkAll(Arb.list(changeArb(), 0..6), registryArb()) { changes: List<ContractChange>, registry: ConsumerRegistry ->
                val once = ConsumerMapper.map(changes, registry, "example-api")
                val twice = ConsumerMapper.map(once.changes, registry, "example-api")
                twice.impacts shouldBe once.impacts
            }
        }

        // --- engine integration ---------------------------------------

        fun schemaNodeArb(depth: Int): Arb<SchemaNode> {
            val leaf: Arb<SchemaNode> =
                Arb.choice(
                    Arb.element("string", "integer", "boolean", "number").map {
                        SchemaNode(
                            NodeType.SCALAR,
                            listOf(it),
                            null,
                            emptyMap(),
                            emptyList(),
                            null,
                            emptyList(),
                            false,
                            null,
                            null,
                            false,
                            "leaf",
                        )
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
                    SchemaNode(
                        NodeType.ARRAY,
                        listOf("array"),
                        null,
                        emptyMap(),
                        emptyList(),
                        it,
                        emptyList(),
                        false,
                        null,
                        null,
                        false,
                        "arr",
                    )
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

        test("engine integration: a wildcard consumer receives every engine change") {
            checkAll(surfaceArb(), surfaceArb()) { old: ContractSurface, new: ContractSurface ->
                val changes = DiffEngine.diff(old, new)
                val registry =
                    ConsumerRegistry(
                        version = 1,
                        consumers =
                            listOf(
                                Consumer(
                                    id = "wildcard",
                                    kind = ConsumerKind.FRONTEND,
                                    contract = "test",
                                    selectors = listOf(parseSelector("wildcard", "*")),
                                ),
                            ),
                    )
                val report = ConsumerMapper.map(changes, registry, "test")
                if (changes.isEmpty()) {
                    report.impacts shouldBe emptyList()
                } else {
                    report.impacts
                        .single()
                        .changes
                        .map { it.change }
                        .toSet() shouldBe changes.toSet()
                }
            }
        }
    })
