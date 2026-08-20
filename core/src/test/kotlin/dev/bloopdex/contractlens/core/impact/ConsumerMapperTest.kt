// Consumer mapping unit tests. The mapper consumes a validated
// registry and a change set and produces deterministic per-consumer
// impacts; these tests pin the semantics (contract/operation selection,
// wildcards, grouping, deduplication, unmatched changes) with
// hand-written changes — no parser involved.

package dev.bloopdex.contractlens.core.impact

import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ChangeTarget
import dev.bloopdex.contractlens.core.diff.ChangeValue
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.changeOrder
import dev.bloopdex.contractlens.core.diff.explainChange
import dev.bloopdex.contractlens.core.registry.Consumer
import dev.bloopdex.contractlens.core.registry.ConsumerKind
import dev.bloopdex.contractlens.core.registry.ConsumerRegistry
import dev.bloopdex.contractlens.core.registry.parseSelector
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun change(
    kind: ChangeKind,
    location: String,
    from: String? = null,
    to: String? = null,
    target: ChangeTarget = ChangeTarget.SCHEMA,
): ContractChange {
    val fromValue = from?.let { ChangeValue(it) }
    val toValue = to?.let { ChangeValue(it) }
    return ContractChange(
        kind = kind,
        target = target,
        location = location,
        sourceLocation = null,
        from = fromValue,
        to = toValue,
        explanation = explainChange(kind, location, fromValue, toValue),
    )
}

private fun consumer(
    id: String,
    contract: String = "example-api",
    selectors: List<String> = listOf("*"),
    kind: ConsumerKind = ConsumerKind.FRONTEND,
): Consumer =
    Consumer(
        id = id,
        kind = kind,
        contract = contract,
        selectors = selectors.map { parseSelector(id, it) },
    )

private fun registryOf(vararg consumers: Consumer): ConsumerRegistry = ConsumerRegistry(version = 1, consumers = consumers.toList())

class ConsumerMapperTest :
    FunSpec({

        test("a change on a selected operation maps to its consumer") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(consumer("example-frontend", selectors = listOf("GET /users/{id}"))),
                    "example-api",
                )
            report.impacts.size shouldBe 1
            val impact = report.impacts.single()
            impact.consumer.id shouldBe "example-frontend"
            val entry = impact.changes.single()
            entry.change shouldBe c
            entry.operation.method shouldBe "get"
            entry.operation.path shouldBe "/users/{id}"
            entry.operation.pathIdentity shouldBe "/users/{}"
            entry.reason shouldBe REASON_THIS_OPERATION
        }

        test("template normalization matches equivalent templates (canonical identity)") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(consumer("example-frontend", selectors = listOf("GET /users/{userId}"))),
                    "example-api",
                )
            report.impacts.size shouldBe 1
        }

        test("a consumer of another contract is not affected") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(consumer("other", contract = "other-api")),
                    "example-api",
                )
            report.impacts shouldBe emptyList()
            report.changes shouldBe listOf(c)
        }

        test("a consumer selecting an unrelated operation is not affected") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(consumer("c", selectors = listOf("POST /users"))),
                    "example-api",
                )
            report.impacts shouldBe emptyList()
            report.changes shouldBe listOf(c)
        }

        test("a wildcard consumer matches every operation") {
            val changes =
                listOf(
                    change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string"),
                    change(ChangeKind.OPERATION_ADDED, "POST /users", to = "POST /users", target = ChangeTarget.OPERATION),
                )
            val report = ConsumerMapper.map(changes, registryOf(consumer("wildcard")), "example-api")
            val impact = report.impacts.single()
            impact.changes.size shouldBe 2
            impact.changes.all { it.reason == REASON_ALL_OPERATIONS } shouldBe true
        }

        test("multiple changes on one consumer are grouped and sorted by changeOrder") {
            val z = change(ChangeKind.PROPERTY_REMOVED, "GET /z → response 200 → schema → properties.a", from = "string")
            val a = change(ChangeKind.PROPERTY_REMOVED, "GET /a → response 200 → schema → properties.a", from = "string")
            val report = ConsumerMapper.map(listOf(z, a), registryOf(consumer("wildcard")), "example-api")
            report.impacts
                .single()
                .changes
                .map { it.change } shouldBe listOf(a, z)
        }

        test("one change affects multiple consumers") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(
                        consumer("consumer-a", selectors = listOf("GET /users/{id}")),
                        consumer("consumer-b", selectors = listOf("GET /users/{id}")),
                    ),
                    "example-api",
                )
            report.impacts.map { it.consumer.id } shouldBe listOf("consumer-a", "consumer-b")
            report.impacts.all { it.changes.single().change == c } shouldBe true
            ConsumerMapper.mappedChangeCount(report.impacts) shouldBe 2
        }

        test("overlapping selectors produce no duplicate records") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(consumer("c", selectors = listOf("*", "GET /users/{id}"))),
                    "example-api",
                )
            report.impacts
                .single()
                .changes.size shouldBe 1
            report.impacts
                .single()
                .changes
                .single()
                .reason shouldBe REASON_ALL_OPERATIONS
        }

        test("duplicate identical selectors collapse to one record") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(consumer("c", selectors = listOf("GET /users/{id}", "GET /users/{id}"))),
                    "example-api",
                )
            report.impacts
                .single()
                .changes.size shouldBe 1
        }

        test("a path change maps to consumers of the old path AND the new path") {
            val c =
                change(
                    ChangeKind.OPERATION_PATH_CHANGED,
                    "GET /users/{id}",
                    from = "/users/{id}",
                    to = "/accounts/{id}",
                    target = ChangeTarget.OPERATION,
                )
            val report =
                ConsumerMapper.map(
                    listOf(c),
                    registryOf(
                        consumer("old-path-consumer", selectors = listOf("GET /users/{id}")),
                        consumer("new-path-consumer", selectors = listOf("GET /accounts/{id}")),
                        consumer("both-consumer", selectors = listOf("GET /users/{id}", "GET /accounts/{id}")),
                    ),
                    "example-api",
                )
            val byId = report.impacts.associateBy { it.consumer.id }
            byId["old-path-consumer"]!!
                .changes
                .single()
                .operation.path shouldBe "/users/{id}"
            byId["new-path-consumer"]!!
                .changes
                .single()
                .operation.path shouldBe "/accounts/{id}"
            byId["both-consumer"]!!.changes.map { it.operation.path }.sorted() shouldBe listOf("/accounts/{id}", "/users/{id}")
        }

        test("an operation removal maps to consumers of the removed operation") {
            val c = change(ChangeKind.OPERATION_REMOVED, "POST /users", from = "POST /users", target = ChangeTarget.OPERATION)
            val report = ConsumerMapper.map(listOf(c), registryOf(consumer("c", selectors = listOf("POST /users"))), "example-api")
            report.impacts
                .single()
                .changes
                .single()
                .operation.path shouldBe "/users"
        }

        test("an operation addition maps to consumers of the added operation") {
            val c = change(ChangeKind.OPERATION_ADDED, "POST /users", to = "POST /users", target = ChangeTarget.OPERATION)
            val report = ConsumerMapper.map(listOf(c), registryOf(consumer("c", selectors = listOf("POST /users"))), "example-api")
            report.impacts
                .single()
                .changes
                .single()
                .operation.path shouldBe "/users"
        }

        test("a change with a location outside the engine grammar maps to nothing but stays visible") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "not-a-location")
            val report = ConsumerMapper.map(listOf(c), registryOf(consumer("wildcard")), "example-api")
            report.impacts shouldBe emptyList()
            report.changes shouldBe listOf(c)
        }

        test("unmatched changes stay visible in the report") {
            val matched = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val unmatched = change(ChangeKind.PROPERTY_REMOVED, "GET /audit → response 200 → schema → properties.x", from = "string")
            val report =
                ConsumerMapper.map(
                    listOf(matched, unmatched),
                    registryOf(consumer("c", selectors = listOf("GET /users/{id}"))),
                    "example-api",
                )
            report.changes.toSet() shouldBe setOf(matched, unmatched)
            report.impacts
                .single()
                .changes
                .single()
                .change shouldBe matched
        }

        test("zero registered consumers produce an empty impact list") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users/{id} → response 200 → schema → properties.email", from = "string")
            val report = ConsumerMapper.map(listOf(c), ConsumerRegistry(version = 1, consumers = emptyList()), "example-api")
            report.impacts shouldBe emptyList()
            report.changes shouldBe listOf(c)
        }

        test("an empty change set produces no impacts") {
            val report = ConsumerMapper.map(emptyList(), registryOf(consumer("wildcard")), "example-api")
            report.impacts shouldBe emptyList()
            report.changes shouldBe emptyList()
        }

        test("the report carries the diffed contract name and the changeOrder-sorted change set") {
            val z = change(ChangeKind.PROPERTY_REMOVED, "GET /z → response 200 → schema → properties.a", from = "string")
            val a = change(ChangeKind.PROPERTY_REMOVED, "GET /a → response 200 → schema → properties.a", from = "string")
            val report = ConsumerMapper.map(listOf(z, a), registryOf(), "example-api")
            report.contract shouldBe "example-api"
            report.changes shouldBe listOf(a, z).sortedWith(changeOrder)
        }
    })
