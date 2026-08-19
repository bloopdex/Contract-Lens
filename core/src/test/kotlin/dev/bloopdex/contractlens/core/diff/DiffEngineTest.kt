// Structural diff engine unit tests: every rule in the Phase 2 scope
// gets at least one case here; the fixture corpus (in :cli) covers the
// same rules end-to-end through real OpenAPI documents.

package dev.bloopdex.contractlens.core.diff

import dev.bloopdex.contractlens.core.model.Constraints
import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun scalar(type: String, location: String = "s"): SchemaNode =
    SchemaNode(NodeType.SCALAR, listOf(type), null, emptyMap(), emptyList(), null, emptyList(), false, null, null, false, location)

private fun obj(
    properties: Map<String, SchemaNode>,
    required: List<String> = emptyList(),
    location: String = "s",
): SchemaNode =
    SchemaNode(NodeType.OBJECT, listOf("object"), null, properties, required, null, emptyList(), false, null, null, false, location)

private fun array(items: SchemaNode, location: String = "s"): SchemaNode =
    SchemaNode(NodeType.ARRAY, listOf("array"), null, emptyMap(), emptyList(), items, emptyList(), false, null, null, false, location)

private fun op(
    method: String,
    path: String,
    parameters: List<Parameter> = emptyList(),
    requestBody: RequestBody? = null,
    responses: Map<String, Response> = emptyMap(),
    pathIdentity: String = path.replace(Regex("\\{[^}]*\\}"), "{}"),
): Operation = Operation(method, path, pathIdentity, parameters, requestBody, responses, "paths.$path.$method")

private fun surface(vararg ops: Operation): ContractSurface =
    ContractSurface("test", "openapi", "3.0.3", ops.toList())

private fun jsonResponse(schema: SchemaNode): Map<String, Response> =
    mapOf("200" to Response(mapOf("application/json" to schema), "r"))

private fun jsonBody(schema: SchemaNode, required: Boolean = true): RequestBody =
    RequestBody(required, mapOf("application/json" to schema))

private fun param(
    name: String,
    locationIn: String = "query",
    required: Boolean = false,
    schema: SchemaNode? = scalar("string"),
): Parameter = Parameter(name, locationIn, required, schema, "p.$name")

private fun kinds(changes: List<ContractChange>): List<ChangeKind> = changes.map { it.kind }

class DiffEngineTest :
    FunSpec({

        test("operation added and removed") {
            val old = surface(op("get", "/users"))
            val new = surface(op("get", "/users"), op("get", "/health"))
            val changes = DiffEngine.diff(old, new)
            kinds(changes) shouldBe listOf(ChangeKind.OPERATION_ADDED)
            changes.single().location shouldBe "GET /health"

            kinds(DiffEngine.diff(new, old)) shouldBe listOf(ChangeKind.OPERATION_REMOVED)
        }

        test("path template parameter rename is a path change, not add/remove") {
            val old = surface(op("get", "/users/{id}"))
            val new = surface(op("get", "/users/{userId}"))
            kinds(DiffEngine.diff(old, new)) shouldBe listOf(ChangeKind.OPERATION_PATH_CHANGED)
        }

        test("a structural path change removes the old operation and adds the new one") {
            val old = surface(op("get", "/users"))
            val new = surface(op("get", "/users/{id}"))
            kinds(DiffEngine.diff(old, new)) shouldBe listOf(ChangeKind.OPERATION_REMOVED, ChangeKind.OPERATION_ADDED)
        }

        test("parameter added, removed, requiredness changed") {
            val old = surface(op("get", "/users", parameters = listOf(param("limit"))))
            val new = surface(op("get", "/users", parameters = listOf(param("limit", required = true), param("page"))))
            val changes = DiffEngine.diff(old, new)
            changes.map { it.kind } shouldBe listOf(
                ChangeKind.PARAMETER_REQUIRED_CHANGED,
                ChangeKind.PARAMETER_ADDED,
            )
            changes.single { it.kind == ChangeKind.PARAMETER_ADDED }.location shouldBe "GET /users → parameter \"page\" (query)"
        }

        test("parameter location change is one structural fact") {
            val old = surface(op("get", "/users", parameters = listOf(param("limit", "query"))))
            val new = surface(op("get", "/users", parameters = listOf(param("limit", "header"))))
            val changes = DiffEngine.diff(old, new)
            kinds(changes) shouldBe listOf(ChangeKind.PARAMETER_LOCATION_CHANGED)
            changes.single().from?.summary shouldBe "query"
            changes.single().to?.summary shouldBe "header"
        }

        test("parameter schema presence change") {
            val old = surface(op("get", "/users", parameters = listOf(param("limit", schema = null))))
            val new = surface(op("get", "/users", parameters = listOf(param("limit", schema = scalar("integer")))))
            val changes = DiffEngine.diff(old, new)
            kinds(changes) shouldBe listOf(ChangeKind.PARAMETER_SCHEMA_CHANGED)
        }

        test("request body added, removed, requiredness changed") {
            val old = surface(op("post", "/users"))
            val new = surface(op("post", "/users", requestBody = jsonBody(obj(mapOf("name" to scalar("string"))))))
            kinds(DiffEngine.diff(old, new)) shouldBe listOf(ChangeKind.REQUEST_BODY_ADDED)

            val requiredFlip = DiffEngine.diff(
                surface(op("post", "/users", requestBody = jsonBody(obj(emptyMap()), required = false))),
                surface(op("post", "/users", requestBody = jsonBody(obj(emptyMap()), required = true))),
            )
            kinds(requiredFlip) shouldBe listOf(ChangeKind.REQUEST_BODY_REQUIRED_CHANGED)
        }

        test("content type added and removed") {
            val old = surface(op("post", "/users", requestBody = jsonBody(scalar("string"))))
            val new = surface(op("post", "/users", requestBody = RequestBody(true, mapOf("application/xml" to scalar("string")))))
            kinds(DiffEngine.diff(old, new)) shouldBe listOf(ChangeKind.CONTENT_TYPE_REMOVED, ChangeKind.CONTENT_TYPE_ADDED)
        }

        test("response added and removed") {
            val old = surface(op("get", "/users", responses = jsonResponse(scalar("string"))))
            val new = surface(op("get", "/users", responses = jsonResponse(scalar("string")) + ("201" to Response(emptyMap(), "r"))))
            kinds(DiffEngine.diff(old, new)) shouldBe listOf(ChangeKind.RESPONSE_ADDED)
            kinds(DiffEngine.diff(new, old)) shouldBe listOf(ChangeKind.RESPONSE_REMOVED)
        }

        test("property added and removed") {
            val oldObj = obj(mapOf("a" to scalar("string")))
            val newObj = obj(mapOf("a" to scalar("string"), "b" to scalar("string")))
            val changes = DiffEngine.diff(
                surface(op("get", "/users", responses = jsonResponse(oldObj))),
                surface(op("get", "/users", responses = jsonResponse(newObj))),
            )
            changes.map { it.kind } shouldBe listOf(ChangeKind.PROPERTY_ADDED)
            changes.single().location shouldBe "GET /users → response 200 → schema → properties.b"
        }

        test("nested property type change reports the precise leaf location") {
            val old = obj(mapOf("profile" to obj(mapOf("address" to obj(mapOf("postalCode" to scalar("string")))))))
            val new = obj(mapOf("profile" to obj(mapOf("address" to obj(mapOf("postalCode" to scalar("integer")))))))
            val changes = DiffEngine.diff(
                surface(op("get", "/users", responses = jsonResponse(old))),
                surface(op("get", "/users", responses = jsonResponse(new))),
            )
            kinds(changes) shouldBe listOf(ChangeKind.TYPE_CHANGED)
            changes.single().location shouldBe "GET /users → response 200 → schema → properties.profile → properties.address → properties.postalCode"
            changes.single().from?.summary shouldBe "string"
            changes.single().to?.summary shouldBe "integer"
        }

        test("array item type change is identified inside items") {
            val old = array(scalar("string"))
            val new = array(scalar("integer"))
            val changes = DiffEngine.diff(
                surface(op("get", "/users", responses = jsonResponse(old))),
                surface(op("get", "/users", responses = jsonResponse(new))),
            )
            kinds(changes) shouldBe listOf(ChangeKind.TYPE_CHANGED)
            changes.single().location shouldBe "GET /users → response 200 → schema → items"
        }

        test("nullability and enum changes are distinguishable facts") {
            val oldEnum = SchemaNode(NodeType.ENUM, listOf("string"), null, emptyMap(), emptyList(), null, listOf("A", "B", "C"), false, null, null, false, "e")
            val newEnum = oldEnum.copy(enumValues = listOf("A", "B"))
            val removed = DiffEngine.diff(
                surface(op("get", "/users", responses = jsonResponse(oldEnum))),
                surface(op("get", "/users", responses = jsonResponse(newEnum))),
            )
            kinds(removed) shouldBe listOf(ChangeKind.ENUM_CHANGED)
            removed.single().from?.summary shouldBe "A, B, C"
            removed.single().to?.summary shouldBe "A, B"

            val added = DiffEngine.diff(
                surface(op("get", "/users", responses = jsonResponse(newEnum))),
                surface(op("get", "/users", responses = jsonResponse(oldEnum))),
            )
            added.single().from?.summary shouldBe "A, B"
            added.single().to?.summary shouldBe "A, B, C"
        }

        test("constraint change lists exactly the changed fields") {
            val old = scalar("integer").copy(constraints = Constraints(minimum = 1.0, maxLength = 20))
            val new = scalar("integer").copy(constraints = Constraints(minimum = 10.0, maxLength = 20))
            val changes = DiffEngine.diff(
                surface(op("get", "/users", responses = jsonResponse(old))),
                surface(op("get", "/users", responses = jsonResponse(new))),
            )
            kinds(changes) shouldBe listOf(ChangeKind.CONSTRAINT_CHANGED)
            changes.single().from?.summary shouldBe "minimum: 1.0"
            changes.single().to?.summary shouldBe "minimum: 10.0"
        }

        test("required-property addition and removal are separate from property add/remove") {
            val old = obj(mapOf("a" to scalar("string")), required = emptyList())
            val new = obj(mapOf("a" to scalar("string")), required = listOf("a"))
            kinds(DiffEngine.diff(surface(op("get", "/x", responses = jsonResponse(old))), surface(op("get", "/x", responses = jsonResponse(new))))) shouldBe
                listOf(ChangeKind.REQUIRED_PROPERTY_ADDED)
        }

        test("a rename is reported as independent remove/add, never as a rename") {
            val old = obj(mapOf("oldField" to scalar("string")))
            val new = obj(mapOf("newField" to scalar("string")))
            val changes = DiffEngine.diff(
                surface(op("get", "/users", responses = jsonResponse(old))),
                surface(op("get", "/users", responses = jsonResponse(new))),
            )
            kinds(changes) shouldBe listOf(ChangeKind.PROPERTY_ADDED, ChangeKind.PROPERTY_REMOVED)
        }

        test("identical contracts produce no changes") {
            val s = surface(op("get", "/users", parameters = listOf(param("limit")), responses = jsonResponse(obj(mapOf("a" to scalar("string"))))))
            DiffEngine.diff(s, s) shouldBe emptyList()
        }

        test("changes are deterministically ordered by location, kind, from, to") {
            val old = surface(op("get", "/users", responses = jsonResponse(obj(mapOf("b" to scalar("string"), "a" to scalar("string"))))))
            val new = surface(op("get", "/users", responses = jsonResponse(obj(mapOf("b" to scalar("integer"), "a" to scalar("string"), "c" to scalar("string"))))))
            val changes = DiffEngine.diff(old, new)
            changes shouldBe changes.sortedWith(changeOrder)
            changes.map { it.location } shouldBe listOf(
                "GET /users → response 200 → schema → properties.b",
                "GET /users → response 200 → schema → properties.c",
            )
        }

        test("explanations are deterministic and self-contained") {
            val old = surface(op("get", "/users", responses = jsonResponse(scalar("string"))))
            val new = surface(op("get", "/users", responses = jsonResponse(scalar("integer"))))
            val change = DiffEngine.diff(old, new).single()
            change.explanation shouldBe "type changed from string to integer at GET /users → response 200 → schema"
        }
    })
