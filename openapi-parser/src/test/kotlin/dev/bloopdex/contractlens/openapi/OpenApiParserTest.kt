// Parser facade tests against OpenAPI 3.0/3.1 fixtures and the negative
// corpus (malformed, unsupported versions, broken refs, status-key
// collisions). Determinism is pinned by comparing canonical JSON bytes
// across repeated parses.

package dev.bloopdex.contractlens.openapi

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.NodeType
import dev.bloopdex.contractlens.core.model.pathIdentity
import dev.bloopdex.contractlens.core.serialization.canonicalJsonBytes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Path

class OpenApiParserTest :
    FunSpec({

        fun fixture(relative: String): Path = Path.of(requireNotNull(javaClass.getResource("/fixtures/$relative")).toURI())

        val parser = OpenApiParser()

        test("parses an OpenAPI 3.0 document into the canonical model") {
            val surface = parser.parse(fixture("openapi30/users-api.yaml"), "users")

            surface.kind shouldBe "openapi"
            surface.formatVersion shouldBe "3.0.3"
            surface.operations.map { "${it.method} ${it.path}" } shouldBe
                listOf("get /users", "post /users", "get /users/{id}")

            // Path identity: /users/{id} regardless of the parameter name.
            surface.operations.single { it.path == "/users/{id}" }.pathIdentity shouldBe "/users/{}"
            pathIdentity("/users/{id}") shouldBe "/users/{}"

            // Parameter inheritance: path-level `id` exists, and the
            // operation-level override (integer) wins over path-level (string).
            val getOne = surface.operations.single { it.path == "/users/{id}" }
            val id = getOne.parameters.single { it.name == "id" }
            id.`in` shouldBe "path"
            id.required shouldBe true
            id.schema?.types shouldBe listOf("integer")

            // A $ref'd parameter (PageToken) resolves by name.
            val list = surface.operations.single { it.path == "/users" && it.method == "get" }
            list.parameters.map { it.name } shouldBe listOf("limit", "page")
            list.parameters
                .single { it.name == "limit" }
                .schema
                ?.constraints
                ?.minimum shouldBe 1.0

            // A $ref'd response resolves with its content, and the resolved
            // schema keeps the ref target as explanatory metadata.
            val notFound = list.responses.getValue("404")
            notFound.content.keys shouldBe listOf("application/json")
            val errorSchema = notFound.content.getValue("application/json")
            errorSchema.nodeType shouldBe NodeType.OBJECT
            errorSchema.properties.keys shouldBe listOf("code", "message")
            errorSchema.required shouldBe listOf("message")

            // Nested $ref resolution + refTarget stamping.
            val create = surface.operations.single { it.path == "/users" && it.method == "post" }
            val body = create.requestBody
            body?.required shouldBe true
            val newUser = body?.content?.getValue("application/json")
            newUser?.required shouldBe listOf("email")
            newUser?.properties?.getValue("role")?.enumValues shouldBe listOf("admin", "editor", "viewer")
            val userResponse =
                create.responses
                    .getValue("201")
                    .content
                    .getValue("application/json")
            userResponse.refTarget shouldBe "#/components/schemas/User"
            userResponse.properties.getValue("address").refTarget shouldBe "#/components/schemas/Address"
            userResponse.properties
                .getValue("address")
                .properties.keys shouldBe listOf("city", "street")
            userResponse.properties.getValue("tags").nodeType shouldBe NodeType.ARRAY
            userResponse.properties
                .getValue("tags")
                .items
                ?.types shouldBe listOf("string")

            // Locations are present for explainability.
            newUser?.location shouldBe
                "paths./users.post.requestBody.content.application/json.schema"
        }

        test("parses an OpenAPI 3.1 document with 3.1 features") {
            val surface = parser.parse(fixture("openapi31/users-api.yaml"), "users")

            surface.formatVersion shouldBe "3.1.0"
            val list = surface.operations.single { it.path == "/users" && it.method == "get" }
            // 3.1 numeric exclusiveMinimum lands in constraints.minimum.
            list.parameters
                .single { it.name == "limit" }
                .schema
                ?.constraints
                ?.minimum shouldBe 0.0

            val user =
                surface.operations
                    .single { it.path == "/users" && it.method == "post" }
                    .responses
                    .getValue("201")
                    .content
                    .getValue("application/json")
            // 3.1 `type: [string, 'null']` normalizes to types=[string], nullable=true.
            val nickname = user.properties.getValue("nickname")
            nickname.types shouldBe listOf("string")
            nickname.nullable shouldBe true
        }

        test("terminates recursive refs with REF nodes instead of recursing forever") {
            val surface = parser.parse(fixture("openapi30/recursive.yaml"), "recursive")
            val node =
                surface.operations
                    .single()
                    .responses
                    .getValue("200")
                    .content
                    .getValue("application/json")
            node.nodeType shouldBe NodeType.OBJECT
            val children = node.properties.getValue("children")
            children.nodeType shouldBe NodeType.ARRAY
            children.items?.nodeType shouldBe NodeType.REF
            children.items?.refTarget shouldBe "#/components/schemas/Node"
        }

        test("is deterministic: repeated parses produce identical canonical bytes") {
            val first = canonicalJsonBytes(parser.parse(fixture("openapi30/users-api.yaml"), "users"))
            val second = canonicalJsonBytes(parser.parse(fixture("openapi30/users-api.yaml"), "users"))
            first shouldBe second
        }

        test("rejects Swagger 2.0 with the version error, not by converting it") {
            val e =
                shouldThrow<ContractError.UnsupportedVersion> {
                    parser.parse(fixture("negative/swagger-2.0.yaml"), "legacy")
                }
            e.message shouldContain "Swagger"
        }

        test("rejects unknown contract versions") {
            val e =
                shouldThrow<ContractError.UnsupportedVersion> {
                    parser.parse(fixture("negative/unknown-version.yaml"), "future")
                }
            e.message shouldContain "4.0.0"
        }

        test("rejects a document without a version field") {
            shouldThrow<ContractError.InvalidStructure> {
                parser.parse(fixture("negative/missing-openapi.yaml"), "noversion")
            }
        }

        test("rejects malformed YAML with the malformed-document error") {
            shouldThrow<ContractError.MalformedDocument> {
                parser.parse(fixture("negative/malformed.yaml"), "malformed")
            }
        }

        test("rejects unresolved references") {
            val e =
                shouldThrow<ContractError.UnresolvedReference> {
                    parser.parse(fixture("negative/broken-ref.yaml"), "broken")
                }
            e.message shouldContain "#/components/schemas/Missing"
        }

        test("rejects remote/multi-file references (a recorded limitation)") {
            shouldThrow<ContractError.UnsupportedReference> {
                parser.parse(fixture("negative/remote-ref.yaml"), "remote")
            }
        }

        test("rejects status keys that collide after normalization") {
            val e =
                shouldThrow<ContractError.InvalidStructure> {
                    parser.parse(fixture("negative/duplicate-status.yaml"), "dup")
                }
            e.message shouldContain "duplicate status keys"
        }

        test("reports missing files with the file-not-found error") {
            val e =
                shouldThrow<ContractError.FileNotFound> {
                    parser.parse(Path.of("does-not-exist.yaml"), "missing")
                }
            e.code shouldBe "FILE_NOT_FOUND"
        }
    })
