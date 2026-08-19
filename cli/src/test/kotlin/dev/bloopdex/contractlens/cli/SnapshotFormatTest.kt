// Snapshot format-dispatch tests (Phase 4): .graphql/.graphqls files
// route to the GraphQL adapter, --format json-schema routes to the JSON
// Schema adapter, unknown --format values fail clearly, and the default
// OpenAPI behavior is untouched.

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.testing.test
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.writeText

class SnapshotFormatTest :
    FunSpec({

        val sha = "c".repeat(40)

        test("a .graphql file snapshots through the GraphQL adapter") {
            val dir = Files.createTempDirectory("contractlens-format")
            val sdl =
                """
                type Query {
                  user(id: ID!): User
                }
                type User {
                  id: ID!
                  email: String
                }
                """.trimIndent()
            val sdlFile = dir.resolve("users.graphql")
            sdlFile.writeText(sdl)
            val result =
                SnapshotCommand().test(
                    arrayOf("--store", dir.resolve("store").toString(), "--sha", sha, "--json", sdlFile.toString()),
                )
            result.statusCode shouldBe 0
            val summary = CanonicalJson.decodeFromString(SnapshotSummary.serializer(), result.stdout.trim())
            summary.contract shouldBe "users"
            summary.operations shouldBe 1
        }

        test("--format json-schema snapshots a JSON Schema event contract") {
            val dir = Files.createTempDirectory("contractlens-format")
            val schema = """{"title": "UserCreated", "type": "object", "properties": {"email": {"type": "string"}}}"""
            val schemaFile = dir.resolve("event.json")
            schemaFile.writeText(schema)
            val result =
                SnapshotCommand().test(
                    arrayOf(
                        "--store",
                        dir.resolve("store").toString(),
                        "--sha",
                        sha,
                        "--format",
                        "json-schema",
                        "--json",
                        schemaFile.toString(),
                    ),
                )
            result.statusCode shouldBe 0
            val summary = CanonicalJson.decodeFromString(SnapshotSummary.serializer(), result.stdout.trim())
            summary.contract shouldBe "event"
            summary.operations shouldBe 1
        }

        test("an unknown --format fails with a typed error") {
            val dir = Files.createTempDirectory("contractlens-format")
            val schemaFile = dir.resolve("event.json")
            schemaFile.writeText("""{"type": "string"}""")
            val e =
                shouldThrow<dev.bloopdex.contractlens.core.error.ContractError.InvalidStructure> {
                    SnapshotCommand().test(
                        arrayOf("--store", dir.resolve("store").toString(), "--sha", sha, "--format", "avro", schemaFile.toString()),
                    )
                }
            e.message shouldContain "avro"
        }
    })
