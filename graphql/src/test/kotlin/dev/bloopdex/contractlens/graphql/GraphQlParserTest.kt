// GraphQL SDL adapter tests (Phase 4 groundwork): the documented
// mapping — query/mutation root fields, arguments as parameters,
// enums/scalars/objects, recursion guards, nullability — and every
// failure path with the existing typed error model.

package dev.bloopdex.contractlens.graphql

import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.model.NodeType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class GraphQlParserTest :
    FunSpec({

        val parser = GraphQlParser()

        test("query and mutation fields become operations with arguments and data schemas") {
            val surface =
                parser.parse(
                    """
                    type Query {
                      user(id: ID!): User
                      search(term: String, limit: Int): [String!]
                    }
                    type Mutation {
                      createUser(input: CreateUserInput!): User
                    }
                    type User {
                      id: ID!
                      email: String
                      role: Role!
                    }
                    enum Role { ADMIN EDITOR }
                    input CreateUserInput {
                      email: String!
                      role: Role
                    }
                    scalar DateTime
                    """.trimIndent(),
                    "users",
                )
            surface.kind shouldBe "graphql"
            surface.operations.map { "${it.method} ${it.path}" } shouldBe
                listOf(
                    "mutation mutation.createUser",
                    "query query.search",
                    "query query.user",
                )

            val userQuery = surface.operations.first { it.path == "query.user" }
            userQuery.parameters.single().name shouldBe "id"
            userQuery.parameters.single().required shouldBe true
            userQuery.parameters.single().`in` shouldBe "argument"
            userQuery.parameters
                .single()
                .schema
                ?.types shouldBe listOf("string")

            val userSchema =
                userQuery.responses
                    .getValue("data")
                    .content.values
                    .single()
            userSchema.nodeType shouldBe NodeType.OBJECT
            userSchema.properties.keys shouldBe setOf("id", "email", "role")
            userSchema.required shouldBe listOf("id", "role")
            userSchema.properties.getValue("role").nodeType shouldBe NodeType.ENUM
            userSchema.properties.getValue("role").enumValues shouldBe listOf("ADMIN", "EDITOR")
            userSchema.properties.getValue("email").nullable shouldBe true
            userSchema.properties.getValue("id").nullable shouldBe false
        }

        test("a list return type projects to an array schema") {
            val surface =
                parser.parse(
                    """
                    type Query {
                      search: [String!]
                    }
                    """.trimIndent(),
                    "search",
                )
            val schema =
                surface.operations
                    .single()
                    .responses
                    .getValue("data")
                    .content.values
                    .single()
            schema.nodeType shouldBe NodeType.ARRAY
            schema.items?.types shouldBe listOf("string")
            schema.items?.nullable shouldBe false
        }

        test("recursive types become REF nodes instead of recursing forever") {
            val surface =
                parser.parse(
                    """
                    type Query {
                      org: Org
                    }
                    type Org {
                      name: String!
                      parent: Org
                    }
                    """.trimIndent(),
                    "org",
                )
            val schema =
                surface.operations
                    .single()
                    .responses
                    .getValue("data")
                    .content.values
                    .single()
            schema.properties.getValue("parent").nodeType shouldBe NodeType.REF
            schema.properties.getValue("parent").refTarget shouldBe "graphql:Org"
        }

        test("custom scalars become scalar nodes typed with their name") {
            val surface =
                parser.parse(
                    """
                    type Query {
                      now: DateTime
                    }
                    scalar DateTime
                    """.trimIndent(),
                    "time",
                )
            val schema =
                surface.operations
                    .single()
                    .responses
                    .getValue("data")
                    .content.values
                    .single()
            schema.nodeType shouldBe NodeType.SCALAR
            schema.types shouldBe listOf("DateTime")
        }

        test("malformed SDL fails with a typed error") {
            val e =
                shouldThrow<ContractError.MalformedDocument> {
                    parser.parse("type Query {", "broken")
                }
            e.code shouldBe "MALFORMED_DOCUMENT"
        }

        test("a document without Query or Mutation fails clearly") {
            val e =
                shouldThrow<ContractError.InvalidStructure> {
                    parser.parse("type User { id: ID }", "users")
                }
            e.message shouldContain "no 'Query' or 'Mutation'"
        }

        test("an unknown referenced type fails clearly") {
            val e =
                shouldThrow<ContractError.InvalidStructure> {
                    parser.parse("type Query { u: Missing }", "users")
                }
            e.message shouldContain "Missing"
        }

        test("parsing is deterministic") {
            val sdl =
                """
                type Query { a: String }
                """.trimIndent()
            parser.parse(sdl, "x") shouldBe parser.parse(sdl, "x")
        }
    })
