// JSON Schema event adapter tests (Phase 4 groundwork): the documented
// mapping — the document becomes one event operation with a "schema"
// response — plus properties/required/enum/items/constraints/nullability,
// ref handling, and the failure paths.

package dev.bloopdex.contractlens.jsonschema

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.NodeType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class JsonSchemaParserTest :
    FunSpec({

        val parser = JsonSchemaParser()

        test("the document projects to one event operation with a schema response") {
            val surface =
                parser.parse(
                    """
                    {
                      "title": "UserCreated",
                      "type": "object",
                      "required": ["email"],
                      "properties": {
                        "email": {"type": "string"},
                        "age": {"type": "integer", "minimum": 0},
                        "tags": {"type": "array", "items": {"type": "string"}},
                        "role": {"type": "string", "enum": ["admin", "user"]},
                        "nickname": {"type": ["string", "null"]}
                      }
                    }
                    """.trimIndent(),
                    "events",
                )
            surface.kind shouldBe "json-schema"
            val event = surface.operations.single()
            event.method shouldBe "event"
            event.path shouldBe "/UserCreated"
            val schema =
                event.responses
                    .getValue("schema")
                    .content.values
                    .single()
            schema.nodeType shouldBe NodeType.OBJECT
            schema.properties.keys shouldBe setOf("email", "age", "tags", "role", "nickname")
            schema.required shouldBe listOf("email")
            schema.properties
                .getValue("age")
                .constraints
                ?.minimum shouldBe 0.0
            schema.properties.getValue("tags").nodeType shouldBe NodeType.ARRAY
            schema.properties
                .getValue("tags")
                .items
                ?.types shouldBe listOf("string")
            schema.properties.getValue("role").nodeType shouldBe NodeType.ENUM
            schema.properties.getValue("nickname").nullable shouldBe true
            schema.properties.getValue("nickname").types shouldBe listOf("string")
        }

        test("defaults set defaultPresent (the classifier's softening rule depends on it)") {
            val surface =
                parser.parse(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "role": {"type": "string", "default": "viewer"}
                      }
                    }
                    """.trimIndent(),
                    "events",
                )
            surface.operations
                .single()
                .responses
                .getValue("schema")
                .content.values
                .single()
                .properties
                .getValue("role")
                .defaultPresent shouldBe true
        }

        test("a local ref becomes a REF node with the pointer preserved") {
            val surface =
                parser.parse(
                    """
                    {
                      "type": "object",
                      "properties": {
                        "owner": {"${'$'}ref": "#/${'$'}defs/user"}
                      }
                    }
                    """.trimIndent(),
                    "events",
                )
            val node =
                surface.operations
                    .single()
                    .responses
                    .getValue("schema")
                    .content.values
                    .single()
                    .properties
                    .getValue("owner")
            node.nodeType shouldBe NodeType.REF
            node.refTarget shouldBe "#/${'$'}defs/user"
        }

        test("a cross-document ref is rejected loudly") {
            shouldThrow<ContractError.InvalidStructure> {
                parser.parse("""{"type": "object", "properties": {"x": {"${'$'}ref": "https://example.com/x.json"}}}""", "events")
            }
        }

        test("malformed JSON fails with a typed error") {
            val e =
                shouldThrow<ContractError.MalformedDocument> {
                    parser.parse("{not json", "events")
                }
            e.code shouldBe "MALFORMED_DOCUMENT"
        }

        test("a non-object document fails clearly") {
            shouldThrow<ContractError.InvalidStructure> {
                parser.parse("""[1, 2, 3]""", "events")
            }
        }

        test("a document without a title uses the contract name") {
            val surface = parser.parse("""{"type": "string"}""", "payments")
            surface.operations.single().path shouldBe "/payments"
        }

        test("parsing is deterministic") {
            val schema = """{"type": "object", "properties": {"a": {"type": "string"}}}"""
            parser.parse(schema, "x") shouldBe parser.parse(schema, "x")
        }
    })
