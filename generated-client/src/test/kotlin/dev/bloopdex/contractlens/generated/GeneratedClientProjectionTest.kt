// Generated-client projection unit tests (Phase 4, ADR-006): the
// deterministic generator conventions — method naming, merged request
// objects, normalized return type, style recording.

package dev.bloopdex.contractlens.generated

import dev.bloopdex.contractlens.core.model.ContractSurface
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.Operation
import dev.bloopdex.contractlens.core.model.Parameter
import dev.bloopdex.contractlens.core.model.RequestBody
import dev.bloopdex.contractlens.core.model.Response
import dev.bloopdex.contractlens.core.model.SchemaNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private fun scalar(
    type: String,
    location: String = "leaf",
): SchemaNode =
    SchemaNode(NodeType.SCALAR, listOf(type), null, emptyMap(), emptyList(), null, emptyList(), false, null, null, false, location)

private fun operation(
    method: String,
    path: String,
    parameters: List<Parameter> = emptyList(),
    body: RequestBody? = null,
    responses: Map<String, Response> = emptyMap(),
): Operation =
    Operation(
        method = method,
        path = path,
        pathIdentity = path.replace(Regex("\\{[^}]*\\}"), "{}"),
        parameters = parameters,
        requestBody = body,
        responses = responses,
        location = "paths.$path.$method",
    )

private fun surface(vararg operations: Operation): ContractSurface = ContractSurface("api", "openapi", "3.0.3", operations.toList())

class GeneratedClientProjectionTest :
    FunSpec({

        test("method naming follows the documented convention") {
            GeneratedClientProjection.generatedMethodName("get", "/users/{id}") shouldBe "getUsersById"
            GeneratedClientProjection.generatedMethodName("post", "/sessions/{sessionId}/messages") shouldBe
                "postSessionsBySessionIdMessages"
            GeneratedClientProjection.generatedMethodName("delete", "/billing/subscriptions/{plan-id}") shouldBe
                "deleteBillingSubscriptionsByPlanId"
        }

        test("operations project to client methods with merged request objects and a return schema") {
            val projected =
                GeneratedClientProjection.project(
                    surface(
                        operation(
                            "get",
                            "/users/{id}",
                            parameters =
                                listOf(
                                    Parameter("id", "path", true, scalar("string"), "p.id"),
                                    Parameter("limit", "query", false, scalar("integer"), "p.limit"),
                                ),
                            responses =
                                mapOf(
                                    "200" to
                                        Response(
                                            mapOf(
                                                "application/json" to
                                                    SchemaNode(
                                                        NodeType.OBJECT,
                                                        listOf("object"),
                                                        null,
                                                        mapOf("email" to scalar("string", "email")),
                                                        listOf("email"),
                                                        null,
                                                        emptyList(),
                                                        false,
                                                        null,
                                                        null,
                                                        false,
                                                        "user",
                                                    ),
                                            ),
                                            "r.200",
                                        ),
                                ),
                        ),
                    ),
                    GeneratorStyle.TYPESCRIPT,
                )
            projected.kind shouldBe "generated-client"
            projected.formatVersion shouldBe "ts"
            val clientMethod = projected.operations.single()
            clientMethod.method shouldBe "get"
            clientMethod.path shouldBe "client.getUsersById"
            val request = clientMethod.requestBody!!
            request.required shouldBe true
            request.content.values
                .single()
                .properties.keys shouldBe setOf("id", "limit")
            request.content.values
                .single()
                .required shouldBe listOf("id")
            clientMethod.responses.keys shouldBe setOf("return")
            clientMethod.responses
                .getValue("return")
                .content.values
                .single()
                .properties.keys shouldBe setOf("email")
        }

        test("a request body becomes the 'body' property of the merged request") {
            val projected =
                GeneratedClientProjection.project(
                    surface(
                        operation(
                            "post",
                            "/users",
                            body =
                                RequestBody(
                                    true,
                                    mapOf(
                                        "application/json" to
                                            SchemaNode(
                                                NodeType.OBJECT,
                                                listOf("object"),
                                                null,
                                                mapOf("name" to scalar("string", "name")),
                                                emptyList(),
                                                null,
                                                emptyList(),
                                                false,
                                                null,
                                                null,
                                                false,
                                                "body",
                                            ),
                                    ),
                                ),
                        ),
                    ),
                    GeneratorStyle.KOTLIN,
                )
            projected.formatVersion shouldBe "kotlin"
            val request = projected.operations.single().requestBody!!
            request.required shouldBe true
            request.content.values
                .single()
                .properties.keys shouldBe setOf("body")
            request.content.values
                .single()
                .required shouldBe listOf("body")
        }

        test("operations without responses get a void return schema") {
            val projected =
                GeneratedClientProjection.project(
                    surface(operation("get", "/health")),
                    GeneratorStyle.JAVA,
                )
            projected.formatVersion shouldBe "java"
            val returnSchema =
                projected.operations
                    .single()
                    .responses
                    .getValue("return")
                    .content.values
                    .single()
            returnSchema.nodeType shouldBe NodeType.ANY
        }

        test("projection is deterministic and stable across runs") {
            val surface =
                surface(
                    operation(
                        "get",
                        "/users/{id}",
                        parameters = listOf(Parameter("id", "path", true, scalar("string"), "p.id")),
                        responses = mapOf("200" to Response(mapOf("application/json" to scalar("object", "user")), "r.200")),
                    ),
                )
            GeneratedClientProjection.project(surface, GeneratorStyle.TYPESCRIPT) shouldBe
                GeneratedClientProjection.project(surface, GeneratorStyle.TYPESCRIPT)
        }

        test("parameters without a schema project to an untyped property") {
            val projected =
                GeneratedClientProjection.project(
                    surface(
                        operation(
                            "get",
                            "/search",
                            parameters = listOf(Parameter("q", "query", false, null, "p.q")),
                        ),
                    ),
                    GeneratorStyle.TYPESCRIPT,
                )
            val request = projected.operations.single().requestBody!!
            request.required shouldBe false
            request.content.values
                .single()
                .properties
                .getValue("q")
                .nodeType shouldBe NodeType.ANY
        }
    })
