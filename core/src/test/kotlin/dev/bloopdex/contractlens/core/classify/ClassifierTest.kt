// Classifier unit tests (Phase 4): every ADR-001 rule is pinned here
// with hand-written changes, including the two surface-contextual rules
// (added-parameter requiredness, default-softened required properties)
// and the conservative fallbacks. Direction comes from the engine's
// location grammar, exactly as produced by DiffEngine.

package dev.bloopdex.contractlens.core.classify

import dev.bloopdex.contractlens.core.diff.ChangeKind
import dev.bloopdex.contractlens.core.diff.ChangeTarget
import dev.bloopdex.contractlens.core.diff.ChangeValue
import dev.bloopdex.contractlens.core.diff.ContractChange
import dev.bloopdex.contractlens.core.diff.explainChange
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.SchemaNode
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

private fun scalar(
    type: String,
    defaultPresent: Boolean = false,
): SchemaNode =
    SchemaNode(
        nodeType = NodeType.SCALAR,
        types = listOf(type),
        format = null,
        properties = emptyMap(),
        required = emptyList(),
        items = null,
        enumValues = emptyList(),
        nullable = false,
        refTarget = null,
        constraints = null,
        defaultPresent = defaultPresent,
        location = "leaf",
    )

private fun parameterSurface(required: Boolean): ContractSurface =
    ContractSurface(
        name = "test",
        kind = "openapi",
        formatVersion = "3.0.3",
        operations =
            listOf(
                Operation(
                    method = "get",
                    path = "/users",
                    pathIdentity = "/users",
                    parameters =
                        listOf(
                            Parameter(
                                name = "limit",
                                `in` = "query",
                                required = required,
                                schema = scalar("integer"),
                                location = "p.limit",
                            ),
                        ),
                    requestBody = null,
                    responses = emptyMap(),
                    location = "paths./users.get",
                ),
            ),
    )

private fun defaultPropertySurface(defaultPresent: Boolean): ContractSurface =
    ContractSurface(
        name = "test",
        kind = "openapi",
        formatVersion = "3.0.3",
        operations =
            listOf(
                Operation(
                    method = "post",
                    path = "/users",
                    pathIdentity = "/users",
                    parameters = emptyList(),
                    requestBody =
                        RequestBody(
                            required = true,
                            content =
                                mapOf(
                                    "application/json" to
                                        SchemaNode(
                                            nodeType = NodeType.OBJECT,
                                            types = listOf("object"),
                                            format = null,
                                            properties = mapOf("role" to scalar("string", defaultPresent)),
                                            required = emptyList(),
                                            items = null,
                                            enumValues = emptyList(),
                                            nullable = false,
                                            refTarget = null,
                                            constraints = null,
                                            defaultPresent = false,
                                            location = "body",
                                        ),
                                ),
                        ),
                    responses = emptyMap(),
                    location = "paths./users.post",
                ),
            ),
    )

private fun emptySurface(): ContractSurface = ContractSurface("test", "openapi", "3.0.3", emptyList())

class ClassifierTest :
    FunSpec({

        fun classifyOne(
            c: ContractChange,
            surface: ContractSurface = emptySurface(),
        ): ClassifiedChange = Classifier.classify(listOf(c), emptySurface(), surface).changes.single()

        // --- operations -------------------------------------------------

        test("operation added is non-breaking (minor)") {
            val result = classifyOne(change(ChangeKind.OPERATION_ADDED, "POST /users", to = "POST /users", target = ChangeTarget.OPERATION))
            result.verdict shouldBe Verdict.NON_BREAKING
            result.semver shouldBe SemverLevel.MINOR
        }

        test("operation removed is breaking (major)") {
            val result =
                classifyOne(change(ChangeKind.OPERATION_REMOVED, "POST /users", from = "POST /users", target = ChangeTarget.OPERATION))
            result.verdict shouldBe Verdict.BREAKING
            result.semver shouldBe SemverLevel.MAJOR
        }

        test("a path template variable rename (identity unchanged) is non-breaking") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.OPERATION_PATH_CHANGED,
                        "GET /users/{id}",
                        from = "/users/{id}",
                        to = "/users/{userId}",
                        target = ChangeTarget.OPERATION,
                    ),
                )
            result.verdict shouldBe Verdict.NON_BREAKING
            result.semver shouldBe SemverLevel.PATCH
        }

        test("a real path change is breaking") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.OPERATION_PATH_CHANGED,
                        "GET /users/{id}",
                        from = "/users/{id}",
                        to = "/accounts/{id}",
                        target = ChangeTarget.OPERATION,
                    ),
                )
            result.verdict shouldBe Verdict.BREAKING
            result.semver shouldBe SemverLevel.MAJOR
        }

        // --- parameters -------------------------------------------------

        test("a required parameter added is breaking") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_ADDED,
                        "GET /users → parameter \"limit\" (query)",
                        to = "query",
                        target = ChangeTarget.PARAMETER,
                    ),
                    parameterSurface(required = true),
                )
            result.verdict shouldBe Verdict.BREAKING
        }

        test("an optional parameter added is non-breaking (minor)") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_ADDED,
                        "GET /users → parameter \"limit\" (query)",
                        to = "query",
                        target = ChangeTarget.PARAMETER,
                    ),
                    parameterSurface(required = false),
                )
            result.verdict shouldBe Verdict.NON_BREAKING
            result.semver shouldBe SemverLevel.MINOR
        }

        test("a parameter added that cannot be found in the new surface is review") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_ADDED,
                        "GET /users → parameter \"limit\" (query)",
                        to = "query",
                        target = ChangeTarget.PARAMETER,
                    ),
                )
            result.verdict shouldBe Verdict.REVIEW
        }

        test("a parameter removed is breaking") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_REMOVED,
                        "GET /users → parameter \"limit\" (query)",
                        from = "query",
                        target = ChangeTarget.PARAMETER,
                    ),
                )
            result.verdict shouldBe Verdict.BREAKING
        }

        test("a parameter location move is review (no documented rule)") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_LOCATION_CHANGED,
                        "GET /users → parameter \"limit\" (header)",
                        from = "query",
                        to = "header",
                        target = ChangeTarget.PARAMETER,
                    ),
                )
            result.verdict shouldBe Verdict.REVIEW
            result.semver shouldBe null
        }

        test("a parameter becoming required is breaking; becoming optional is non-breaking") {
            val required =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_REQUIRED_CHANGED,
                        "GET /users → parameter \"limit\" (query)",
                        from = "false",
                        to = "true",
                        target = ChangeTarget.PARAMETER,
                    ),
                )
            required.verdict shouldBe Verdict.BREAKING
            val optional =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_REQUIRED_CHANGED,
                        "GET /users → parameter \"limit\" (query)",
                        from = "true",
                        to = "false",
                        target = ChangeTarget.PARAMETER,
                    ),
                )
            optional.verdict shouldBe Verdict.NON_BREAKING
        }

        test("a parameter type change is breaking; gaining or losing a schema is review") {
            val typed =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_SCHEMA_CHANGED,
                        "GET /users → parameter \"limit\" (query)",
                        from = "integer",
                        to = "string",
                        target = ChangeTarget.PARAMETER,
                    ),
                )
            typed.verdict shouldBe Verdict.BREAKING
            val gained =
                classifyOne(
                    change(
                        ChangeKind.PARAMETER_SCHEMA_CHANGED,
                        "GET /users → parameter \"limit\" (query)",
                        from = "no schema",
                        to = "string",
                        target = ChangeTarget.PARAMETER,
                    ),
                )
            gained.verdict shouldBe Verdict.REVIEW
        }

        // --- request bodies ----------------------------------------------

        test("a required request body added is breaking; an optional one is non-breaking") {
            val required =
                classifyOne(
                    change(
                        ChangeKind.REQUEST_BODY_ADDED,
                        "POST /users → request body",
                        to = "required: true",
                        target = ChangeTarget.REQUEST_BODY,
                    ),
                )
            required.verdict shouldBe Verdict.BREAKING
            val optional =
                classifyOne(
                    change(
                        ChangeKind.REQUEST_BODY_ADDED,
                        "POST /users → request body",
                        to = "required: false",
                        target = ChangeTarget.REQUEST_BODY,
                    ),
                )
            optional.verdict shouldBe Verdict.NON_BREAKING
        }

        test("a request body removed is breaking") {
            val result =
                classifyOne(
                    change(
                        ChangeKind.REQUEST_BODY_REMOVED,
                        "POST /users → request body",
                        from = "required: true",
                        target = ChangeTarget.REQUEST_BODY,
                    ),
                )
            result.verdict shouldBe Verdict.BREAKING
        }

        test("a request body becoming required is breaking; becoming optional is non-breaking") {
            val required =
                classifyOne(
                    change(
                        ChangeKind.REQUEST_BODY_REQUIRED_CHANGED,
                        "POST /users → request body",
                        from = "false",
                        to = "true",
                        target = ChangeTarget.REQUEST_BODY,
                    ),
                )
            required.verdict shouldBe Verdict.BREAKING
            val optional =
                classifyOne(
                    change(
                        ChangeKind.REQUEST_BODY_REQUIRED_CHANGED,
                        "POST /users → request body",
                        from = "true",
                        to = "false",
                        target = ChangeTarget.REQUEST_BODY,
                    ),
                )
            optional.verdict shouldBe Verdict.NON_BREAKING
        }

        // --- content types and responses ---------------------------------

        test("a content type added is non-breaking; removed is breaking") {
            val added =
                classifyOne(
                    change(
                        ChangeKind.CONTENT_TYPE_ADDED,
                        "GET /users → response 200 (content text/csv)",
                        to = "text/csv",
                        target = ChangeTarget.RESPONSE,
                    ),
                )
            added.verdict shouldBe Verdict.NON_BREAKING
            val removed =
                classifyOne(
                    change(
                        ChangeKind.CONTENT_TYPE_REMOVED,
                        "GET /users → response 200 (content text/csv)",
                        from = "text/csv",
                        target = ChangeTarget.RESPONSE,
                    ),
                )
            removed.verdict shouldBe Verdict.BREAKING
        }

        test("a response status added is review; removed is breaking") {
            val added =
                classifyOne(change(ChangeKind.RESPONSE_ADDED, "GET /users → response 404", to = "404", target = ChangeTarget.RESPONSE))
            added.verdict shouldBe Verdict.REVIEW
            val removed =
                classifyOne(change(ChangeKind.RESPONSE_REMOVED, "GET /users → response 404", from = "404", target = ChangeTarget.RESPONSE))
            removed.verdict shouldBe Verdict.BREAKING
        }

        // --- properties --------------------------------------------------

        test("a response property removed is breaking; a request property removed is review") {
            val response =
                classifyOne(change(ChangeKind.PROPERTY_REMOVED, "GET /users → response 200 → schema → properties.email", from = "string"))
            response.verdict shouldBe Verdict.BREAKING
            val request =
                classifyOne(change(ChangeKind.PROPERTY_REMOVED, "POST /users → request body → schema → properties.email", from = "string"))
            request.verdict shouldBe Verdict.REVIEW
        }

        test("a property added is non-breaking (minor)") {
            val result =
                classifyOne(change(ChangeKind.PROPERTY_ADDED, "GET /users → response 200 → schema → properties.nickname", to = "string"))
            result.verdict shouldBe Verdict.NON_BREAKING
            result.semver shouldBe SemverLevel.MINOR
        }

        test(
            "a required request property added is breaking; with a JSON Schema default it is review; a required response property added is review",
        ) {
            val requestBreaking =
                classifyOne(
                    change(
                        ChangeKind.REQUIRED_PROPERTY_ADDED,
                        "POST /users → request body → schema → properties.role",
                        from = "optional",
                        to = "required",
                    ),
                )
            requestBreaking.verdict shouldBe Verdict.BREAKING

            val softened =
                classifyOne(
                    change(
                        ChangeKind.REQUIRED_PROPERTY_ADDED,
                        "POST /users → request body → schema → properties.role",
                        from = "optional",
                        to = "required",
                    ),
                    defaultPropertySurface(defaultPresent = true),
                )
            softened.verdict shouldBe Verdict.REVIEW
            softened.reason shouldBe "the newly required request property carries a JSON Schema default, softening the addition"

            val response =
                classifyOne(
                    change(
                        ChangeKind.REQUIRED_PROPERTY_ADDED,
                        "GET /users → response 200 → schema → properties.role",
                        from = "optional",
                        to = "required",
                    ),
                )
            response.verdict shouldBe Verdict.REVIEW
        }

        test("a required response property removed is breaking; a required request property removed is non-breaking") {
            val response =
                classifyOne(
                    change(
                        ChangeKind.REQUIRED_PROPERTY_REMOVED,
                        "GET /users → response 200 → schema → properties.email",
                        from = "required",
                        to = "optional",
                    ),
                )
            response.verdict shouldBe Verdict.BREAKING
            val request =
                classifyOne(
                    change(
                        ChangeKind.REQUIRED_PROPERTY_REMOVED,
                        "POST /users → request body → schema → properties.email",
                        from = "required",
                        to = "optional",
                    ),
                )
            request.verdict shouldBe Verdict.NON_BREAKING
        }

        test("a type change is breaking in both directions") {
            val response =
                classifyOne(
                    change(
                        ChangeKind.TYPE_CHANGED,
                        "GET /users → response 200 → schema → properties.email",
                        from = "string",
                        to = "integer",
                    ),
                )
            response.verdict shouldBe Verdict.BREAKING
            val request =
                classifyOne(
                    change(
                        ChangeKind.TYPE_CHANGED,
                        "POST /users → request body → schema → properties.email",
                        from = "integer",
                        to = "string",
                    ),
                )
            request.verdict shouldBe Verdict.BREAKING
        }

        test("a type change with undeterminable direction is still breaking (direction-independent rule)") {
            val result =
                classifyOne(change(ChangeKind.TYPE_CHANGED, "GET /users → schema → properties.email", from = "string", to = "integer"))
            result.verdict shouldBe Verdict.BREAKING
        }

        // --- nullability -------------------------------------------------

        test("nullability follows the direction table") {
            val requestBecameNullable =
                classifyOne(
                    change(
                        ChangeKind.NULLABLE_CHANGED,
                        "POST /users → request body → schema → properties.nick",
                        from = "false",
                        to = "true",
                    ),
                )
            requestBecameNullable.verdict shouldBe Verdict.NON_BREAKING
            val requestBecameNonNull =
                classifyOne(
                    change(
                        ChangeKind.NULLABLE_CHANGED,
                        "POST /users → request body → schema → properties.nick",
                        from = "true",
                        to = "false",
                    ),
                )
            requestBecameNonNull.verdict shouldBe Verdict.REVIEW
            val responseBecameNullable =
                classifyOne(
                    change(
                        ChangeKind.NULLABLE_CHANGED,
                        "GET /users → response 200 → schema → properties.nick",
                        from = "false",
                        to = "true",
                    ),
                )
            responseBecameNullable.verdict shouldBe Verdict.REVIEW
            val responseBecameNonNull =
                classifyOne(
                    change(
                        ChangeKind.NULLABLE_CHANGED,
                        "GET /users → response 200 → schema → properties.nick",
                        from = "true",
                        to = "false",
                    ),
                )
            responseBecameNonNull.verdict shouldBe Verdict.NON_BREAKING
        }

        // --- enums -------------------------------------------------------

        test("enum removal is breaking in both directions; request addition is non-breaking; response addition is review") {
            val requestRemoved =
                classifyOne(
                    change(
                        ChangeKind.ENUM_CHANGED,
                        "POST /users → request body → schema → properties.role",
                        from = "admin, editor",
                        to = "admin",
                    ),
                )
            requestRemoved.verdict shouldBe Verdict.BREAKING
            val responseRemoved =
                classifyOne(
                    change(
                        ChangeKind.ENUM_CHANGED,
                        "GET /users → response 200 → schema → properties.role",
                        from = "admin, editor",
                        to = "admin",
                    ),
                )
            responseRemoved.verdict shouldBe Verdict.BREAKING
            val requestAdded =
                classifyOne(
                    change(
                        ChangeKind.ENUM_CHANGED,
                        "POST /users → request body → schema → properties.role",
                        from = "admin",
                        to = "admin, editor",
                    ),
                )
            requestAdded.verdict shouldBe Verdict.NON_BREAKING
            val responseAdded =
                classifyOne(
                    change(
                        ChangeKind.ENUM_CHANGED,
                        "GET /users → response 200 → schema → properties.role",
                        from = "admin",
                        to = "admin, editor",
                    ),
                )
            responseAdded.verdict shouldBe Verdict.REVIEW
        }

        // --- constraints --------------------------------------------------

        test("a tightened constraint is review; a relaxed constraint is non-breaking; both is review") {
            val tightened =
                classifyOne(
                    change(
                        ChangeKind.CONSTRAINT_CHANGED,
                        "GET /users → response 200 → schema → properties.page",
                        from = "minimum: 1.0",
                        to = "minimum: 10.0",
                    ),
                )
            tightened.verdict shouldBe Verdict.REVIEW
            val relaxed =
                classifyOne(
                    change(
                        ChangeKind.CONSTRAINT_CHANGED,
                        "GET /users → response 200 → schema → properties.page",
                        from = "minimum: 10.0",
                        to = "minimum: 1.0",
                    ),
                )
            relaxed.verdict shouldBe Verdict.NON_BREAKING
            relaxed.semver shouldBe SemverLevel.PATCH
            val both =
                classifyOne(
                    change(
                        ChangeKind.CONSTRAINT_CHANGED,
                        "GET /users → response 200 → schema → properties.page",
                        from = "minimum: 1.0, maximum: 10.0",
                        to = "minimum: 5.0, maximum: 100.0",
                    ),
                )
            both.verdict shouldBe Verdict.REVIEW
        }

        // --- kinds without a documented rule ------------------------------

        test("ref target, default, and items changes are review (no documented rule)") {
            val ref =
                classifyOne(
                    change(
                        ChangeKind.REF_TARGET_CHANGED,
                        "GET /users → response 200 → schema → properties.owner",
                        from = "#/components/schemas/User",
                        to = "#/components/schemas/Admin",
                    ),
                )
            ref.verdict shouldBe Verdict.REVIEW
            val default =
                classifyOne(
                    change(
                        ChangeKind.DEFAULT_CHANGED,
                        "GET /users → response 200 → schema → properties.page",
                        from = "has default",
                        to = "no default",
                    ),
                )
            default.verdict shouldBe Verdict.REVIEW
            val items =
                classifyOne(
                    change(
                        ChangeKind.ITEMS_CHANGED,
                        "GET /users → response 200 → schema → properties.tags",
                        from = "no item schema",
                        to = "string",
                    ),
                )
            items.verdict shouldBe Verdict.REVIEW
        }

        // --- rename candidates -------------------------------------------

        test("a same-type add+remove pair in one schema is a rename candidate: both members are review") {
            val removed = change(ChangeKind.PROPERTY_REMOVED, "GET /users → response 200 → schema → properties.email", from = "string")
            val added = change(ChangeKind.PROPERTY_ADDED, "GET /users → response 200 → schema → properties.emailAddress", to = "string")
            val report = Classifier.classify(listOf(removed, added), emptySurface(), emptySurface())
            report.changes.map { it.verdict } shouldBe listOf(Verdict.REVIEW, Verdict.REVIEW)
            report.changes.map { it.semver } shouldBe listOf(null, null)
            report.changes.all { "possible rename" in it.reason } shouldBe true
        }

        test("a removal without a same-type addition stays breaking; a different-type addition does not pair") {
            val removed = change(ChangeKind.PROPERTY_REMOVED, "GET /users → response 200 → schema → properties.email", from = "string")
            val added = change(ChangeKind.PROPERTY_ADDED, "GET /users → response 200 → schema → properties.age", to = "integer")
            val report = Classifier.classify(listOf(removed, added), emptySurface(), emptySurface())
            report.changes.first { it.change.kind == ChangeKind.PROPERTY_REMOVED }.verdict shouldBe Verdict.BREAKING
            report.changes.first { it.change.kind == ChangeKind.PROPERTY_ADDED }.verdict shouldBe Verdict.NON_BREAKING
        }

        // --- report mechanics ---------------------------------------------

        test("classification fills the verdict slot on copies and never mutates the input") {
            val c = change(ChangeKind.PROPERTY_REMOVED, "GET /users → response 200 → schema → properties.email", from = "string")
            val report = Classifier.classify(listOf(c), emptySurface(), emptySurface())
            report.changes
                .single()
                .change.verdict shouldBe "breaking"
            c.verdict shouldBe null
        }

        test("the summary counts verdicts and reports the highest semver level") {
            val changes =
                listOf(
                    change(ChangeKind.OPERATION_REMOVED, "DELETE /users", from = "DELETE /users", target = ChangeTarget.OPERATION),
                    change(ChangeKind.PROPERTY_ADDED, "GET /users → response 200 → schema → properties.x", to = "string"),
                    change(
                        ChangeKind.CONSTRAINT_CHANGED,
                        "GET /users → response 200 → schema → properties.page",
                        from = "minimum: 5.0",
                        to = "minimum: 1.0",
                    ),
                    change(
                        ChangeKind.DEFAULT_CHANGED,
                        "GET /users → response 200 → schema → properties.x",
                        from = "has default",
                        to = "no default",
                    ),
                )
            val report = Classifier.classify(changes, emptySurface(), emptySurface())
            report.summary.total shouldBe 4
            report.summary.breaking shouldBe 1
            report.summary.nonBreaking shouldBe 2
            report.summary.review shouldBe 1
            report.summary.semver shouldBe SemverLevel.MAJOR
        }

        test("the report preserves changeOrder") {
            val z = change(ChangeKind.PROPERTY_REMOVED, "GET /z → response 200 → schema → properties.a", from = "string")
            val a = change(ChangeKind.PROPERTY_REMOVED, "GET /a → response 200 → schema → properties.a", from = "string")
            val report = Classifier.classify(listOf(z, a), emptySurface(), emptySurface())
            report.changes.map { it.change.copy(verdict = null) } shouldBe listOf(a, z)
        }
    })
