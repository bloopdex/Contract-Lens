// Phase 5 robustness + security regression tests: resource limits on
// every untrusted input, the snapshot-store filename boundary, and the
// redaction boundary (no raw spec content — descriptions/examples —
// may leak into snapshots or reports).

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.testing.test
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.error.MAX_INPUT_BYTES
import dev.bloopdex.contractlens.graphql.GraphQlParser
import dev.bloopdex.contractlens.jsonschema.JsonSchemaParser
import dev.bloopdex.contractlens.openapi.OpenApiParser
import dev.bloopdex.contractlens.registry.RegistryParser
import dev.bloopdex.contractlens.registry.UsageParser
import dev.bloopdex.contractlens.snapshot.SnapshotIdentity
import dev.bloopdex.contractlens.snapshot.SnapshotStore
import dev.bloopdex.contractlens.snapshot.buildSnapshot
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private const val SECRET_MARKER = "SECRET_MARKER_9f3a1b7c"

private val SPEC_WITH_SECRETS =
    """
    openapi: 3.0.3
    info:
      title: Secretful API
      version: 1.0.0
      description: contains $SECRET_MARKER
    paths:
      /ping:
        get:
          description: also $SECRET_MARKER
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema:
                    type: object
                    description: schema $SECRET_MARKER
                    properties:
                      status:
                        type: string
                        description: property $SECRET_MARKER
                        example: $SECRET_MARKER
    """.trimIndent()

class RobustnessTest :
    FunSpec({

        val sha = "a".repeat(40)

        fun tempSpec(
            text: String,
            name: String = "spec.yaml",
        ): Path {
            val dir = Files.createTempDirectory("contractlens-robust")
            val path = dir.resolve(name)
            path.writeText(text)
            return path
        }

        fun oversizedText(prefix: String): String = prefix + "x".repeat(MAX_INPUT_BYTES)

        test("an oversized OpenAPI document is rejected before parsing (INPUT_TOO_LARGE)") {
            val path = tempSpec(oversizedText("openapi: 3.0.3\ninfo: {title: big, version: 1.0.0}\npaths: {}\n"))
            val e = shouldThrow<ContractError.InputTooLarge> { OpenApiParser().parse(path, "big") }
            e.code shouldBe "INPUT_TOO_LARGE"
        }

        test("an oversized GraphQL document is rejected before parsing") {
            shouldThrow<ContractError.InputTooLarge> { GraphQlParser().parse(oversizedText("type Query { a: String }"), "big") }
        }

        test("an oversized JSON Schema document is rejected before parsing") {
            shouldThrow<ContractError.InputTooLarge> { JsonSchemaParser().parse(oversizedText("{\"type\": \"string\"}"), "big") }
        }

        test("an oversized registry is rejected before parsing") {
            shouldThrow<ContractError.InputTooLarge> { RegistryParser.parse(oversizedText("version: 1\nconsumers: []\n"), "registry.yaml") }
        }

        test("an oversized usage graph is rejected before parsing") {
            shouldThrow<ContractError.InputTooLarge> { UsageParser.parse(oversizedText("version: 1\nconsumers: []\n"), "usage.yaml") }
        }

        test("an empty OpenAPI document fails with a typed error, never a crash") {
            val path = tempSpec("")
            val e = shouldThrow<ContractError> { OpenApiParser().parse(path, "empty") }
            e.code shouldBe "INVALID_STRUCTURE" // empty YAML loads as null: "the document root must be an object"
        }

        test("an empty GraphQL document fails with a typed error") {
            val e = shouldThrow<ContractError> { GraphQlParser().parse("", "empty") }
            e.code shouldBe "MALFORMED_DOCUMENT" // SchemaParser rejects empty input
        }

        test("an empty registry fails with a typed error") {
            shouldThrow<ContractError.RegistryInvalid> { RegistryParser.parse("", "registry.yaml") }
        }

        test("a contract name that looks like a path cannot escape the snapshot store") {
            val dir = Files.createTempDirectory("contractlens-store")
            val store = SnapshotStore(dir)
            val document =
                buildSnapshot(
                    contract = "../../../evil",
                    sourcePath = null,
                    identity = SnapshotIdentity(kind = "git-commit", sha = sha),
                    capturedAt = "2026-08-19T00:00:00Z",
                    surface =
                        dev.bloopdex.contractlens.core.model
                            .ContractSurface("evil", "openapi", "3.0.3", emptyList()),
                )
            val written = store.save(document)
            written.parent shouldBe dir
            Files.list(dir).use { stream ->
                stream.allMatch { it.parent == dir } shouldBe true
            }
            // And nothing escaped outside the store directory.
            store.load("../../../evil", sha) shouldBe document
        }

        test("spec descriptions and examples never leak into snapshots (redaction boundary)") {
            val dir = Files.createTempDirectory("contractlens-redact")
            val spec = dir.resolve("secretful.yaml")
            spec.writeText(SPEC_WITH_SECRETS)
            val storeDir = dir.resolve("store")
            val snap = SnapshotCommand().test(arrayOf("--store", storeDir.toString(), "--sha", sha, spec.toString()))
            snap.statusCode shouldBe 0
            val snapshotFile = storeDir.resolve("secretful@$sha.snapshot.json")
            Files.readString(snapshotFile) shouldNotContain SECRET_MARKER
        }

        test("diff and impact reports never leak spec descriptions or examples") {
            val dir = Files.createTempDirectory("contractlens-redact")
            val spec = dir.resolve("secretful.yaml")
            spec.writeText(SPEC_WITH_SECRETS)
            val modified = dir.resolve("secretful2.yaml")
            modified.writeText(
                SPEC_WITH_SECRETS.replace("type: string", "type: integer").replace("example: $SECRET_MARKER", "example: replaced"),
            )
            val storeDir = dir.resolve("store")
            val registryFile = dir.resolve("registry.yaml")
            registryFile.writeText("version: 1\nconsumers: []\n")
            val oldResult =
                SnapshotCommand().test(
                    arrayOf("--store", storeDir.toString(), "--name", "secretful", "--sha", sha, spec.toString()),
                )
            oldResult.statusCode shouldBe 0
            val newResult =
                SnapshotCommand().test(
                    arrayOf("--store", storeDir.toString(), "--name", "secretful", "--sha", "b".repeat(40), modified.toString()),
                )
            newResult.statusCode shouldBe 0
            val oldSnapshot = storeDir.resolve("secretful@$sha.snapshot.json")
            val newSnapshot = storeDir.resolve("secretful@${"b".repeat(40)}.snapshot.json")

            val diff = DiffCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--classify"))
            diff.stdout shouldNotContain SECRET_MARKER

            val impact =
                ImpactCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", registryFile.toString()),
                )
            impact.stdout shouldNotContain SECRET_MARKER
        }
    })
