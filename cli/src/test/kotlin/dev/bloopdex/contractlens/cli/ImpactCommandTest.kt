// `contractlens impact` CLI tests (Phase 3): the full snapshot -> diff ->
// registry -> mapping workflow, JSON output, the honesty boundary in the
// human report, and every failure path (registry corruption, contract
// mismatch, missing files).

package dev.bloopdex.contractlens.cli

import com.github.ajalt.clikt.testing.test
import dev.bloopdex.contractlens.core.error.ContractError
import dev.bloopdex.contractlens.core.serialization.CanonicalJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

private fun specWithEmail() =
    """
    openapi: 3.0.3
    info:
      title: Thorn API
      version: 1.0.0
    paths:
      /users/{id}:
        get:
          parameters:
            - name: id
              in: path
              required: true
              schema:
                type: string
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema:
                    type: object
                    required: [id]
                    properties:
                      id:
                        type: string
                      email:
                        type: string
      /health:
        get:
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema:
                    type: object
                    properties:
                      status:
                        type: string
    """.trimIndent()

private fun specWithoutEmail() =
    """
    openapi: 3.0.3
    info:
      title: Thorn API
      version: 1.0.0
    paths:
      /users/{id}:
        get:
          parameters:
            - name: id
              in: path
              required: true
              schema:
                type: string
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema:
                    type: object
                    required: [id]
                    properties:
                      id:
                        type: string
      /health:
        get:
          responses:
            '200':
              description: ok
              content:
                application/json:
                  schema:
                    type: object
                    properties:
                      status:
                        type: string
    """.trimIndent()

private fun specWithoutHealth() = specWithoutEmail().replace(Regex("(?s)  /health:.*"), "").trimEnd() + "\n"

class ImpactCommandTest :
    FunSpec({

        val sha = "d".repeat(40)

        fun capture(
            dir: Path,
            spec: String,
            name: String,
            shaValue: String,
        ): Path {
            val specFile = dir.resolve("$name.yaml")
            specFile.writeText(spec)
            val storeDir = dir.resolve("store-$name")
            val result =
                SnapshotCommand().test(
                    arrayOf("--store", storeDir.toString(), "--name", name, "--sha", shaValue, specFile.toString()),
                )
            result.statusCode shouldBe 0
            return storeDir.resolve("$name@$shaValue.snapshot.json")
        }

        fun registryFile(
            dir: Path,
            content: String,
        ): Path {
            val path = dir.resolve("registry.yaml")
            path.writeText(content)
            return path
        }

        fun wildcardRegistry(dir: Path): Path =
            registryFile(
                dir,
                """
                version: 1
                consumers:
                  - id: thornwa-frontend
                    kind: frontend
                    contract: thorn-api
                    operations:
                      - GET /users/{id}
                """.trimIndent(),
            )

        test("the full workflow maps a change to its declared consumer and classifies it") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithoutEmail(), "thorn-api", "e".repeat(40))
            // A response property was removed: the classifier says
            // breaking, which main() maps to exit 1 (the reserved code).
            val result =
                ImpactCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", wildcardRegistry(dir).toString()),
                )
            result.statusCode shouldBe 0
            result.stdout shouldContain "changes: 1"
            result.stdout shouldContain "classification: 1 breaking, 0 non-breaking, 0 review"
            result.stdout shouldContain "semver: major"
            result.stdout shouldContain "registered consumers: 1"
            result.stdout shouldContain "affected consumers: 1"
            result.stdout shouldContain "consumer thornwa-frontend (frontend)"
            result.stdout shouldContain "PROPERTY_REMOVED"
            result.stdout shouldContain "[breaking] (major)"
            result.stdout shouldContain "verdict: the response no longer guarantees a property consumers may read"
            result.stdout shouldContain "reason: consumer declares this operation"
            result.stdout shouldContain "note: unregistered consumers are not visible to ContractLens."
        }

        test("--json emits the versioned report and preserves the change set") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithoutEmail(), "thorn-api", "e".repeat(40))
            val result =
                ImpactCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", wildcardRegistry(dir).toString(), "--json"),
                )
            result.statusCode shouldBe 0
            val report = CanonicalJson.decodeFromString(ImpactJsonReport.serializer(), result.stdout.trim())
            report.format shouldBe "contractlens-impact"
            report.version shouldBe 2
            report.summary.changes shouldBe 1
            report.summary.affectedConsumers shouldBe 1
            report.summary.mappedChanges shouldBe 1
            report.summary.breaking shouldBe 1
            report.summary.semver shouldBe dev.bloopdex.contractlens.core.classify.SemverLevel.MAJOR
            report.changes
                .single()
                .kind.name shouldBe "PROPERTY_REMOVED"
            report.classified
                .single()
                .verdict shouldBe dev.bloopdex.contractlens.core.classify.Verdict.BREAKING
            report.impacts
                .single()
                .consumer.id shouldBe "thornwa-frontend"
            report.note shouldContain "unregistered consumers"
        }

        test("an identical diff reports zero affected consumers but keeps the honesty note") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithEmail(), "thorn-api", "e".repeat(40))
            val result =
                ImpactCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", wildcardRegistry(dir).toString()),
                )
            result.statusCode shouldBe 0
            result.stdout shouldContain "changes: 0"
            result.stdout shouldContain "affected consumers: 0"
            result.stdout shouldContain "note: unregistered consumers are not visible to ContractLens."
        }

        test("unmapped changes stay visible in the human report") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithoutHealth(), "thorn-api", "e".repeat(40))
            val result =
                ImpactCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", wildcardRegistry(dir).toString()),
                )
            result.statusCode shouldBe 0
            result.stdout shouldContain "changes: 2"
            result.stdout shouldContain "classification: 2 breaking, 0 non-breaking, 0 review"
            result.stdout shouldContain "affected consumers: 1"
            result.stdout shouldContain "unmapped changes: 1"
            result.stdout shouldContain "OPERATION_REMOVED"
            result.stdout shouldContain "[breaking]"
        }

        test("snapshots of different contracts are refused with a typed error") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "api-v1", sha)
            val newSnapshot = capture(dir, specWithEmail(), "api-v2", "e".repeat(40))
            val e =
                shouldThrow<ContractError.ContractMismatch> {
                    ImpactCommand().test(
                        arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", wildcardRegistry(dir).toString()),
                    )
                }
            e.code shouldBe "CONTRACT_MISMATCH"
        }

        test("a malformed registry fails with a typed error") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithoutEmail(), "thorn-api", "e".repeat(40))
            val badRegistry = registryFile(dir, "version: 1\nconsumers: [\n")
            val e =
                shouldThrow<ContractError.RegistryInvalid> {
                    ImpactCommand().test(
                        arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", badRegistry.toString()),
                    )
                }
            e.code shouldBe "REGISTRY_INVALID"
        }

        test("a registry with duplicate consumer ids fails with a typed error") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithoutEmail(), "thorn-api", "e".repeat(40))
            val dupRegistry =
                registryFile(
                    dir,
                    """
                    version: 1
                    consumers:
                      - id: dup
                        kind: frontend
                        contract: thorn-api
                        operations: ["*"]
                      - id: dup
                        kind: frontend
                        contract: thorn-api
                        operations: ["*"]
                    """.trimIndent(),
                )
            val e =
                shouldThrow<ContractError.RegistryDuplicateId> {
                    ImpactCommand().test(
                        arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", dupRegistry.toString()),
                    )
                }
            e.code shouldBe "REGISTRY_DUPLICATE_ID"
        }

        test("a missing registry file is a usage error") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithoutEmail(), "thorn-api", "e".repeat(40))
            val result =
                ImpactCommand().test(
                    arrayOf(oldSnapshot.toString(), newSnapshot.toString(), "--registry", dir.resolve("missing.yaml").toString()),
                )
            result.statusCode shouldBe 1
            result.stderr shouldContain "missing.yaml"
        }

        test("a missing --registry option is a usage error") {
            val dir = Files.createTempDirectory("contractlens-impact")
            val oldSnapshot = capture(dir, specWithEmail(), "thorn-api", sha)
            val newSnapshot = capture(dir, specWithoutEmail(), "thorn-api", "e".repeat(40))
            val result = ImpactCommand().test(arrayOf(oldSnapshot.toString(), newSnapshot.toString()))
            result.statusCode shouldBe 1
        }
    })
