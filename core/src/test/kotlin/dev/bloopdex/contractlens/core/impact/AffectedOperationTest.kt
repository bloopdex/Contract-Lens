// affectedOperations() grammar tests (Phase 3): the derivation of the
// changed operation's canonical identity from the engine's pinned
// location grammar. Locations outside the grammar yield no operation —
// never a crash, never a guessed match.

package dev.bloopdex.contractlens.core.impact

import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ChangeTarget
import dev.bloopdex.contractlens.core.diff.ChangeValue
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.explainChange
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun grammarChange(
    kind: ChangeKind,
    target: ChangeTarget,
    location: String,
    from: String? = null,
    to: String? = null,
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

class AffectedOperationTest :
    FunSpec({

        test("a nested schema location yields the operation of its first segment") {
            val op =
                affectedOperations(
                    grammarChange(
                        ChangeKind.PROPERTY_REMOVED,
                        ChangeTarget.SCHEMA,
                        "GET /users/{id} → response 200 → schema → properties.email",
                    ),
                ).single()
            op.method shouldBe "get"
            op.path shouldBe "/users/{id}"
            op.pathIdentity shouldBe "/users/{}"
        }

        test("a parameter location yields its operation") {
            val op =
                affectedOperations(
                    grammarChange(
                        ChangeKind.PARAMETER_REQUIRED_CHANGED,
                        ChangeTarget.PARAMETER,
                        "GET /users/{id} → parameter \"limit\" (query)",
                    ),
                ).single()
            op.method shouldBe "get"
            op.path shouldBe "/users/{id}"
        }

        test("a request body location yields its operation") {
            val op =
                affectedOperations(
                    grammarChange(ChangeKind.REQUEST_BODY_ADDED, ChangeTarget.REQUEST_BODY, "POST /users → request body"),
                ).single()
            op.method shouldBe "post"
            op.path shouldBe "/users"
        }

        test("an operation removal yields the removed operation") {
            val op = affectedOperations(grammarChange(ChangeKind.OPERATION_REMOVED, ChangeTarget.OPERATION, "POST /users")).single()
            op.method shouldBe "post"
            op.path shouldBe "/users"
        }

        test("a path change yields both the old and the new operation") {
            val ops =
                affectedOperations(
                    grammarChange(
                        ChangeKind.OPERATION_PATH_CHANGED,
                        ChangeTarget.OPERATION,
                        "GET /users/{id}",
                        from = "/users/{id}",
                        to = "/accounts/{id}",
                    ),
                )
            ops.map { it.path } shouldBe listOf("/accounts/{id}", "/users/{id}")
            ops.all { it.method == "get" } shouldBe true
        }

        test("a malformed location yields no operation (never a crash, never a guess)") {
            affectedOperations(grammarChange(ChangeKind.PROPERTY_REMOVED, ChangeTarget.SCHEMA, "nonsense")) shouldBe emptyList()
            affectedOperations(grammarChange(ChangeKind.PROPERTY_REMOVED, ChangeTarget.SCHEMA, "GET")) shouldBe emptyList()
            affectedOperations(grammarChange(ChangeKind.PROPERTY_REMOVED, ChangeTarget.SCHEMA, "GET users")) shouldBe emptyList()
        }
    })
